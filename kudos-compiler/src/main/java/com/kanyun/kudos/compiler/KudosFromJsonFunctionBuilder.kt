/*
 * Copyright (C) 2023 Kanyun, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kanyun.kudos.compiler

import com.kanyun.kudos.compiler.KudosNames.JSON_READER_PEEK_CALLABLE_ID
import com.kanyun.kudos.compiler.KudosNames.JSON_READER_SKIP_VALUE_CALLABLE_ID
import com.kanyun.kudos.compiler.KudosNames.JSON_TOKEN_CLASS_ID
import com.kanyun.kudos.compiler.KudosNames.JSON_TOKEN_NULL_IDENTIFIER
import com.kanyun.kudos.compiler.KudosNames.KUDOS_JSON_ADAPTER_CLASS_ID
import com.kanyun.kudos.compiler.KudosNames.KUDOS_JSON_NAME_NAME
import com.kanyun.kudos.compiler.utils.irThis
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.ir.kClassReference
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class KudosFromJsonFunctionBuilder(
    private val irClass: IrClass,
    private val irFunction: IrFunction,
    private val pluginContext: IrPluginContext,
    private val kudosStatusField: IrField?,
    private val validatorFunction: IrSimpleFunction?,
    startOffset: Int = SYNTHETIC_OFFSET,
    endOffset: Int = SYNTHETIC_OFFSET,
) : IrBlockBodyBuilder(pluginContext, Scope(irFunction.symbol), startOffset, endOffset) {

    init {
        irFunction.body = doBuild()
    }

    private val jsonReader = irFunction.valueParameters.first()

    fun generateBody(): KudosFromJsonFunctionBuilder {
        val fields = mutableMapOf<IrField, String>()
        irClass.properties.forEach { property ->
            if (property.isDelegated) return@forEach
            val backingField = property.backingField ?: return@forEach
            val annotationJsonName = ((property.getAnnotation(KUDOS_JSON_NAME_NAME)
                ?.getValueArgument(Name.identifier("name"))
                    as? IrConst<*>)?.value as? String).orEmpty()
            fields[backingField] = annotationJsonName
        }
        +irCall(
            pluginContext.referenceFunctions(
                CallableId(FqName("android.util"), FqName("JsonReader"), Name.identifier("beginObject")),
            ).first(),
        ).apply {
            dispatchReceiver = irGet(jsonReader)
        }
        +irWhile().apply {
            condition = irCall(
                pluginContext.referenceFunctions(
                    CallableId(FqName("android.util"), FqName("JsonReader"), Name.identifier("hasNext")),
                ).first(),
            ).apply {
                dispatchReceiver = irGet(jsonReader)
            }
            body = irBlock {
                val name = irTemporary(
                    irCall(
                        pluginContext.referenceFunctions(
                            CallableId(FqName("android.util"), FqName("JsonReader"), Name.identifier("nextName")),
                        ).first(),
                    ).apply {
                        dispatchReceiver = irGet(jsonReader)
                    },
                )
                val jsonReaderPeekExpression = irCall(pluginContext.referenceFunctions(JSON_READER_PEEK_CALLABLE_ID).first()).apply {
                    dispatchReceiver = irGet(jsonReader)
                }
                val jsonTokenClass = pluginContext.referenceClass(JSON_TOKEN_CLASS_ID)!!
                val jsonTokenNullEntry = jsonTokenClass.owner.declarations.filterIsInstance<IrEnumEntry>().first {
                    it.name == JSON_TOKEN_NULL_IDENTIFIER
                }
                val jsonTokenNullExpression = IrGetEnumValueImpl(
                    startOffset,
                    endOffset,
                    jsonTokenClass.defaultType,
                    jsonTokenNullEntry.symbol,
                )
                +irIfThen(
                    context.irBuiltIns.unitType,
                    irEquals(jsonReaderPeekExpression, jsonTokenNullExpression),
                    irBlock {
                        +irCall(
                            pluginContext.referenceFunctions(JSON_READER_SKIP_VALUE_CALLABLE_ID).first(),
                        ).apply {
                            dispatchReceiver = irGet(jsonReader)
                        }
                        +irContinue(this@apply)
                    },
                )
                val branches = ArrayList<IrBranch>()
                fields.forEach { (field, annotationJsonName) ->
                    val jsonName = annotationJsonName.ifEmpty { field.name.asString() }
                    branches.add(
                        irBranch(
                            irEquals(irGet(name), irString(jsonName)),
                            irBlock {
                                +irSetField(irFunction.irThis(), field, getNextValue(field))
                                if (kudosStatusField != null) {
                                    +irCall(
                                        pluginContext.referenceFunctions(
                                            CallableId(FqName("java.util"), FqName("Map"), Name.identifier("put")),
                                        ).first(),
                                    ).apply {
                                        putValueArgument(0, irString(field.name.asString()))
                                        putValueArgument(1, irNotEquals(irGetField(irFunction.irThis(), field), irNull()))
                                        dispatchReceiver = irGetField(irFunction.irThis(), kudosStatusField)
                                    }
                                }
                            },
                        ),
                    )
                }
                branches.add(
                    irElseBranch(
                        irCall(pluginContext.referenceFunctions(JSON_READER_SKIP_VALUE_CALLABLE_ID).first()).apply {
                            dispatchReceiver = irGet(jsonReader)
                        },
                    ),
                )
                +irWhen(context.irBuiltIns.unitType, branches)
            }
        }
        +irCall(
            pluginContext.referenceFunctions(
                CallableId(FqName("android.util"), FqName("JsonReader"), Name.identifier("endObject")),
            ).first(),
        ).apply {
            dispatchReceiver = irGet(jsonReader)
        }
        if (validatorFunction != null && kudosStatusField != null) {
            +irCall(validatorFunction.symbol).apply {
                putValueArgument(0, irGetField(irFunction.irThis(), kudosStatusField))
                dispatchReceiver = irFunction.irThis()
            }
        }
        +irReturn(
            irFunction.irThis(),
        )
        return this
    }

    private fun getJsonReaderNextSymbol(type: String): IrSimpleFunctionSymbol {
        return pluginContext.referenceFunctions(
            CallableId(
                FqName("android.util"),
                FqName("JsonReader"),
                Name.identifier("next$type"),
            ),
        ).first()
    }

    private fun getNextValue(field: IrField): IrExpression {
        return if (field.type.isSubtypeOfClass(context.irBuiltIns.stringClass)) {
            irCall(getJsonReaderNextSymbol("String")).apply {
                dispatchReceiver = irGet(jsonReader)
            }
        } else if (field.type.isSubtypeOfClass(context.irBuiltIns.longClass)) {
            irCall(getJsonReaderNextSymbol("Long")).apply {
                dispatchReceiver = irGet(jsonReader)
            }
        } else if (field.type.isSubtypeOfClass(context.irBuiltIns.intClass)) {
            irCall(getJsonReaderNextSymbol("Int")).apply {
                dispatchReceiver = irGet(jsonReader)
            }
        } else if (field.type.isSubtypeOfClass(context.irBuiltIns.doubleClass)) {
            irCall(getJsonReaderNextSymbol("Double")).apply {
                dispatchReceiver = irGet(jsonReader)
            }
        } else if (field.type.isSubtypeOfClass(context.irBuiltIns.floatClass)) {
            irCall(
                pluginContext.referenceFunctions(
                    CallableId(FqName("kotlin.text"), Name.identifier("toFloat")),
                ).first().owner,
            ).apply {
                extensionReceiver = irCall(getJsonReaderNextSymbol("String")).apply {
                    dispatchReceiver = irGet(jsonReader)
                }
            }
        } else if (field.type.isSubtypeOfClass(context.irBuiltIns.booleanClass)) {
            irCall(getJsonReaderNextSymbol("Boolean")).apply {
                dispatchReceiver = irGet(jsonReader)
            }
        } else if (
            field.type.isSubtypeOfClass(context.irBuiltIns.listClass) ||
            field.type.isSubtypeOfClass(context.irBuiltIns.arrayClass) ||
            field.type.isSubtypeOfClass(context.irBuiltIns.setClass) ||
            field.type.isSubtypeOfClass(context.irBuiltIns.mapClass) ||
            field.type.isSubtypeOfClass(
                pluginContext.referenceClass(KUDOS_JSON_ADAPTER_CLASS_ID)!!,
            )
        ) {
            irCall(
                pluginContext.referenceFunctions(
                    CallableId(FqName("com.kanyun.kudos.json.reader.adapter"), Name.identifier("parseKudosObject")),
                ).first(),
            ).apply {
                putValueArgument(0, irGet(jsonReader))
                putValueArgument(1, getParameterizedType(field.type))
            }
        } else {
            throw Exception("Kudos UnSupported type ${field.type.classFqName}")
        }
    }

    private fun getParameterizedType(type: IrType): IrExpression {
        var typeArguments = listOf<IrTypeProjection>()
        if (type is IrSimpleType) {
            typeArguments = type.arguments.filterIsInstance<IrTypeProjection>()
        }
        if (typeArguments.isEmpty()) {
            return irCall(
                pluginContext.referenceProperties(
                    CallableId(FqName("kotlin.jvm"), Name.identifier("javaObjectType")),
                ).first().owner.getter!!,
            ).apply {
                extensionReceiver = kClassReference(type)
            }
        }
        val irVararg = irVararg(
            pluginContext.referenceClass(ClassId(FqName("java.lang.reflect"), Name.identifier("Type")))!!.defaultType,
            typeArguments.map { getParameterizedType(it.type) },
        )
        val typeArray = irCall(
            pluginContext.referenceFunctions(
                CallableId(FqName("kotlin"), Name.identifier("arrayOf")),
            ).first(),
        ).apply {
            putValueArgument(0, irVararg)
        }
        return irCall(
            pluginContext.referenceClass(
                ClassId(
                    FqName("com.kanyun.kudos.json.reader.adapter"),
                    Name.identifier("ParameterizedTypeImpl"),
                ),
            )!!.constructors.single(),
        ).apply {
            putValueArgument(
                0,
                irCall(
                    pluginContext.referenceProperties(
                        CallableId(FqName("kotlin.jvm"), Name.identifier("javaObjectType")),
                    ).first().owner.getter!!,
                ).apply {
                    extensionReceiver = kClassReference(type)
                },
            )
            putValueArgument(1, typeArray)
        }
    }
}

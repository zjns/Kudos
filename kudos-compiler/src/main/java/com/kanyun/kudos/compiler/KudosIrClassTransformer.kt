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

import com.kanyun.kudos.compiler.KudosNames.ADAPTER_FACTORY_NAME
import com.kanyun.kudos.compiler.KudosNames.JSON_ADAPTER_NAME
import com.kanyun.kudos.compiler.KudosNames.KUDOS_FIELD_STATUS_MAP_IDENTIFIER
import com.kanyun.kudos.compiler.KudosNames.KUDOS_VALIDATOR_NAME
import com.kanyun.kudos.compiler.options.Options
import com.kanyun.kudos.compiler.utils.addOverride
import com.kanyun.kudos.compiler.utils.hasKudosAnnotation
import com.kanyun.kudos.compiler.utils.irThis
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.*

/**
 * Created by Benny Huo on 2023/9/8
 */
class KudosIrClassTransformer(
    private val context: IrPluginContext,
    private val irClass: IrClass,
    private val noArgConstructors: MutableMap<IrClass, IrConstructor>,
    private val kudosAnnotationValueMap: HashMap<String, List<Int>>,
) {

    private val defaults = HashSet<String>()

    fun transform() {
        if (Options.isGsonEnabled(kudosAnnotationValueMap, irClass.classId?.asString())) {
            generateJsonAdapter()
        }
        generateNoArgConstructor()
        val validatorFunction = if (!Options.disableValidator()) generateValidator() else null
        if (Options.isAndroidJsonReaderEnabled(kudosAnnotationValueMap, irClass.classId?.asString())) {
            generateFromJson(validatorFunction)
        }
    }

    private fun generateJsonAdapter() {
        val jsonAdapter = context.referenceConstructors(ClassId.topLevel(JSON_ADAPTER_NAME)).firstOrNull()
            ?: throw IllegalStateException(
                "Constructors of class ${JSON_ADAPTER_NAME.shortName()} not found while isGsonEnabled is set to true. " +
                    "Please check your dependencies to ensure the existing of the Gson library.",
            )
        irClass.annotations += IrConstructorCallImpl.fromSymbolOwner(
            jsonAdapter.owner.returnType,
            jsonAdapter.owner.symbol,
        ).apply {
            val adapterFactory = context.referenceClass(ClassId.topLevel(ADAPTER_FACTORY_NAME))!!

            putValueArgument(
                0,
                IrClassReferenceImpl(
                    startOffset,
                    endOffset,
                    context.irBuiltIns.kClassClass.starProjectedType,
                    context.irBuiltIns.kClassClass,
                    adapterFactory.defaultType,
                ),
            )
        }
    }

    private fun generateNoArgConstructor() {
        if (needsNoargConstructor(irClass)) {
            irClass.declarations.add(getOrGenerateNoArgConstructor(irClass))
        }
    }

    private fun getOrGenerateNoArgConstructor(klass: IrClass): IrConstructor = noArgConstructors.getOrPut(klass) {
        val superClass = klass.superTypes.mapNotNull(IrType::getClass).singleOrNull { it.kind == ClassKind.CLASS }
            ?: context.irBuiltIns.anyClass.owner

        val superConstructor = if (needsNoargConstructor(superClass)) {
            getOrGenerateNoArgConstructor(superClass)
        } else {
            superClass.constructors.singleOrNull {
                it.isZeroParameterConstructor()
            } ?: error(
                "No noarg super constructor for ${klass.render()}:\n" +
                    superClass.constructors.joinToString("\n") { it.render() },
            )
        }

        context.irFactory.buildConstructor {
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            returnType = klass.defaultType
        }.also { ctor ->
            ctor.parent = klass

            val builder = object : IrBuilderWithScope(context, Scope(ctor.symbol), SYNTHETIC_OFFSET, SYNTHETIC_OFFSET) {
                fun setupDefaultValues(): List<IrStatement> {
                    return klass.primaryConstructor?.valueParameters?.filter {
                        it.defaultValue != null
                    }?.mapNotNull { parameter ->
                        klass.properties.find {
                            it.name == parameter.name
                        }?.backingField?.let { field ->
                            val init = field.initializer?.expression as? IrGetValue
                            if (init?.origin == IrStatementOrigin.INITIALIZE_PROPERTY_FROM_PARAMETER) {
                                defaults += field.name.asString()

                                irSetField(
                                    IrGetValueImpl(
                                        startOffset,
                                        endOffset,
                                        klass.thisReceiver!!.symbol,
                                    ),
                                    field,
                                    parameter.defaultValue!!.expression,
                                )
                            } else {
                                null
                            }
                        }
                    } ?: emptyList()
                }
            }

            ctor.body = context.irFactory.createBlockBody(
                ctor.startOffset,
                ctor.endOffset,
                listOfNotNull(
                    // call super
                    IrDelegatingConstructorCallImpl(
                        ctor.startOffset,
                        ctor.endOffset,
                        context.irBuiltIns.unitType,
                        superConstructor.symbol,
                        0,
                        superConstructor.valueParameters.size,
                    ),
                    // call init blocks
                    IrInstanceInitializerCallImpl(
                        ctor.startOffset,
                        ctor.endOffset,
                        klass.symbol,
                        context.irBuiltIns.unitType,
                    ),
                ) + builder.setupDefaultValues(),
            )
        }
    }

    private fun generateValidator(): IrSimpleFunction? {
        val nonDefaults = ArrayList<String>()
        val collections = ArrayList<IrField>()
        val arrays = ArrayList<IrField>()

        irClass.properties.forEach { property ->
            if (property.isDelegated) return@forEach
            val backingField = property.backingField ?: return@forEach
            val fieldName = backingField.name.asString()

            // do not check property from body. always initialized properly.
            if ((backingField.initializer?.expression as? IrGetValue)?.origin == IrStatementOrigin.INITIALIZE_PROPERTY_FROM_PARAMETER) {
                if (!backingField.type.isNullable() && fieldName !in defaults) {
                    nonDefaults += fieldName
                }
            }

            val type = backingField.type
            if (type is IrSimpleType) {
                // For container types, the only first type argument is the element type.
                if (type.arguments.firstOrNull()?.typeOrNull?.isNullable() == false) {
                    if (type.isSubtypeOfClass(context.irBuiltIns.arrayClass)) {
                        arrays += backingField
                    } else if (type.isSubtypeOfClass(context.irBuiltIns.collectionClass)) {
                        collections += backingField
                    }
                }
            }
        }

        if (nonDefaults.isEmpty() && collections.isEmpty() && arrays.isEmpty()) return null

        val statusType = context.irBuiltIns.mapClass.typeWith(
            context.irBuiltIns.stringType,
            context.irBuiltIns.booleanType,
        )

        val validateFunction = irClass.functions.find {
            it.name.asString() == "validate" && it.valueParameters.singleOrNull {
                it.type == statusType
            } != null
        }

        if (validateFunction?.isFakeOverride == false) {
            return validateFunction
        } else if (validateFunction?.isFakeOverride == true) {
            irClass.declarations.remove(validateFunction)
        }

        irClass.addOverride(KUDOS_VALIDATOR_NAME, "validate", context.irBuiltIns.unitType, Modality.OPEN).apply {
            dispatchReceiverParameter = irClass.thisReceiver!!.copyTo(this)
            val statusParameter = addValueParameter {
                name = Name.identifier("status")
                type = statusType
            }

            val validateField = context.referenceFunctions(
                CallableId(FqName("com.kanyun.kudos.validator"), Name.identifier("validateField")),
            ).first()

            val validateCollection = context.referenceFunctions(
                CallableId(FqName("com.kanyun.kudos.validator"), Name.identifier("validateCollection")),
            ).first()

            val validateArray = context.referenceFunctions(
                CallableId(FqName("com.kanyun.kudos.validator"), Name.identifier("validateArray")),
            ).first()

            body = IrBlockBodyBuilder(context, Scope(symbol), SYNTHETIC_OFFSET, SYNTHETIC_OFFSET).blockBody {
                val status = irGet(statusParameter.type, statusParameter.symbol)

                // region call super
                val superClass = irClass.superClass
                if (superClass != null) {
                    overriddenSymbols.firstOrNull {
                        it.owner.parentClassOrNull == superClass
                    }?.let {
                        +irCall(this@apply, superQualifierSymbol = superClass.symbol).also { call ->
                            call.dispatchReceiver = irThis()
                            call.putValueArgument(0, status)
                        }
                    }
                }
                // endregion

                nonDefaults.forEach { fieldName ->
                    +irCall(validateField).apply {
                        putValueArgument(
                            0,
                            IrConstImpl.string(
                                SYNTHETIC_OFFSET,
                                SYNTHETIC_OFFSET,
                                context.irBuiltIns.stringType,
                                fieldName,
                            ),
                        )
                        putValueArgument(1, status)
                    }
                }

                collections.forEach { field ->
                    +irCall(validateCollection).apply {
                        putValueArgument(
                            0,
                            IrConstImpl.string(
                                SYNTHETIC_OFFSET,
                                SYNTHETIC_OFFSET,
                                context.irBuiltIns.stringType,
                                field.name.asString(),
                            ),
                        )
                        putValueArgument(1, irGetField(irThis(), field))

                        field.type.classFqName?.shortNameOrSpecial()?.asString()?.let { typeName ->
                            putValueArgument(
                                2,
                                IrConstImpl.string(
                                    SYNTHETIC_OFFSET,
                                    SYNTHETIC_OFFSET,
                                    context.irBuiltIns.stringType,
                                    typeName,
                                ),
                            )
                        }
                    }
                }

                arrays.forEach { field ->
                    +irCall(validateArray).apply {
                        putValueArgument(
                            0,
                            IrConstImpl.string(
                                SYNTHETIC_OFFSET,
                                SYNTHETIC_OFFSET,
                                context.irBuiltIns.stringType,
                                field.name.asString(),
                            ),
                        )
                        putValueArgument(
                            1,
                            irGetField(irThis(), field),
                        )
                    }
                }
            }
        }
        return validateFunction
    }

    private fun needsNoargConstructor(declaration: IrClass): Boolean =
        declaration.kind == ClassKind.CLASS &&
            declaration.hasKudosAnnotation() &&
            declaration.constructors.none { it.isZeroParameterConstructor() }

    // Returns true if this constructor is callable with no arguments by JVM rules, i.e. will have descriptor `()V`.
    private fun IrConstructor.isZeroParameterConstructor(): Boolean {
        return valueParameters.all {
            it.defaultValue != null
        } && (valueParameters.isEmpty() || isPrimary || hasAnnotation(JvmNames.JVM_OVERLOADS_FQ_NAME))
    }

    private fun generateFromJson(validatorFunction: IrSimpleFunction?) {
        if (irClass.hasKudosAnnotation()) {
            val fieldType = context.irBuiltIns.mapClass.typeWith(
                context.irBuiltIns.stringClass.defaultType,
                context.irBuiltIns.booleanClass.defaultType,
            )
            val initExpression = context.referenceFunctions(
                CallableId(FqName("kotlin.collections"), Name.identifier("hashMapOf")),
            ).first()
            val kudosStatusField = if (validatorFunction != null) {
                irClass.addField(
                    KUDOS_FIELD_STATUS_MAP_IDENTIFIER,
                    fieldType,
                ).apply {
                    initializer = DeclarationIrBuilder(
                        context,
                        symbol,
                        symbol.owner.startOffset,
                        symbol.owner.endOffset,
                    ).run {
                        irExprBody(irCall(initExpression))
                    }
                }
            } else {
                null
            }
            irClass.functions.singleOrNull {
                it.name.identifier == "fromJson"
            }?.takeIf {
                it.body == null
            }?.let { function ->
                KudosFromJsonFunctionBuilder(irClass, function, context, kudosStatusField, validatorFunction).generateBody()
            }
        }
    }
}

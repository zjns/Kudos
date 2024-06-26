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

package com.kanyun.kudos.compiler.k1

import com.kanyun.kudos.compiler.KudosNames.JSON_READER_CLASS_ID
import com.kanyun.kudos.compiler.KudosNames.JSON_READER_IDENTIFIER
import com.kanyun.kudos.compiler.KudosNames.KUDOS_FROM_JSON_IDENTIFIER
import com.kanyun.kudos.compiler.KudosNames.KUDOS_JSON_ADAPTER
import com.kanyun.kudos.compiler.KudosNames.KUDOS_JSON_ADAPTER_CLASS_ID
import com.kanyun.kudos.compiler.KudosNames.KUDOS_NAME
import com.kanyun.kudos.compiler.KudosNames.KUDOS_VALIDATOR
import com.kanyun.kudos.compiler.KudosNames.KUDOS_VALIDATOR_CLASS_ID
import com.kanyun.kudos.compiler.k1.symbol.FromJsonFunctionDescriptorImpl
import com.kanyun.kudos.compiler.options.Options
import com.kanyun.kudos.compiler.utils.safeAs
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.js.descriptorUtils.getKotlinTypeFqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.constants.IntValue
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.typeUtil.supertypes

class KudosSyntheticResolveExtension(
    private val kudosAnnotationValueMap: HashMap<String, List<Int>>,
) : SyntheticResolveExtension {

    override fun addSyntheticSupertypes(
        thisDescriptor: ClassDescriptor,
        supertypes: MutableList<KotlinType>,
    ) {
        if (thisDescriptor.kind != ClassKind.CLASS) return
        if (thisDescriptor.annotations.hasAnnotation(KUDOS_NAME)) {
            val kudosAnnotation = thisDescriptor.annotations.findAnnotation(KUDOS_NAME)
            val annotationValues = kudosAnnotation?.allValueArguments?.values?.firstOrNull()?.value?.safeAs<List<IntValue>>()?.map {
                if (it.value !in Options.validAnnotationList) {
                    throw IllegalArgumentException("unknown annotation argument ${it.value}")
                }
                it.value
            }
            kudosAnnotationValueMap[thisDescriptor.classId?.asString() ?: ""] = annotationValues ?: emptyList()
            val superTypeNames = supertypes.asSequence().flatMap {
                listOf(it) + it.supertypes()
            }.map {
                it.getKotlinTypeFqName(false)
            }.toSet()

            if (!Options.disableValidator() && KUDOS_VALIDATOR !in superTypeNames) {
                val kudosValidator = thisDescriptor.module.findClassAcrossModuleDependencies(KUDOS_VALIDATOR_CLASS_ID)!!
                supertypes.add(
                    KotlinTypeFactory.simpleNotNullType(
                        TypeAttributes.Empty,
                        kudosValidator,
                        emptyList(),
                    ),
                )
            }
            if (Options.isAndroidJsonReaderEnabled(kudosAnnotationValueMap, thisDescriptor.classId?.asString())) {
                if (KUDOS_JSON_ADAPTER !in superTypeNames) {
                    val kudosJsonAdapter = thisDescriptor.module.findClassAcrossModuleDependencies(KUDOS_JSON_ADAPTER_CLASS_ID)!!
                    supertypes.add(
                        KotlinTypeFactory.simpleNotNullType(
                            TypeAttributes.Empty,
                            kudosJsonAdapter,
                            listOf(TypeProjectionImpl(thisDescriptor.defaultType)),
                        ),
                    )
                }
            }
        }
    }

    override fun getSyntheticFunctionNames(thisDescriptor: ClassDescriptor): List<Name> {
        if (Options.isAndroidJsonReaderEnabled(kudosAnnotationValueMap, thisDescriptor.classId?.asString()) &&
            thisDescriptor.annotations.hasAnnotation(KUDOS_NAME)
        ) {
            return listOf(KUDOS_FROM_JSON_IDENTIFIER)
        }
        return super.getSyntheticFunctionNames(thisDescriptor)
    }

    override fun generateSyntheticMethods(
        thisDescriptor: ClassDescriptor,
        name: Name,
        bindingContext: BindingContext,
        fromSupertypes: List<SimpleFunctionDescriptor>,
        result: MutableCollection<SimpleFunctionDescriptor>,
    ) {
        if (name.identifier == KUDOS_FROM_JSON_IDENTIFIER.identifier) {
            if (thisDescriptor.annotations.hasAnnotation(KUDOS_NAME)) {
                val jsonReaderType: SimpleType = thisDescriptor.module.findClassAcrossModuleDependencies(JSON_READER_CLASS_ID)?.defaultType ?: return

                result += FromJsonFunctionDescriptorImpl(thisDescriptor).apply {
                    val valueParameterDescriptor = ValueParameterDescriptorImpl(
                        containingDeclaration = this,
                        original = null,
                        index = 0,
                        annotations = Annotations.EMPTY,
                        name = JSON_READER_IDENTIFIER,
                        outType = jsonReaderType,
                        declaresDefaultValue = false,
                        isCrossinline = false,
                        isNoinline = false,
                        varargElementType = null,
                        source = SourceElement.NO_SOURCE,
                    )
                    initialize(listOf(valueParameterDescriptor))
                }
            }
        }
    }
}

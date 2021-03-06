/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.ir.copyTypeParametersFrom
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.load.java.JavaVisibilities
import org.jetbrains.kotlin.name.Name

class JvmInnerClassesSupport : InnerClassesSupport {
    private val outerThisDeclarations = HashMap<IrClass, IrField>()
    private val innerClassConstructors = HashMap<IrConstructor, IrConstructor>()
    private val originalInnerClassPrimaryConstructorByClass = HashMap<IrClass, IrConstructor>()

    override fun getOuterThisField(innerClass: IrClass): IrField =
        outerThisDeclarations.getOrPut(innerClass) {
            assert(innerClass.isInner) { "Class is not inner: ${innerClass.dump()}" }
            buildField {
                name = Name.identifier("this$0")
                type = innerClass.parentAsClass.defaultType
                origin = InnerClassesSupport.FIELD_FOR_OUTER_THIS
                visibility = JavaVisibilities.PACKAGE_VISIBILITY
                isFinal = true
            }.apply {
                parent = innerClass
            }
        }

    override fun getInnerClassConstructorWithOuterThisParameter(innerClassConstructor: IrConstructor): IrConstructor {
        val innerClass = innerClassConstructor.parent as IrClass
        assert(innerClass.isInner) { "Class is not inner: ${(innerClassConstructor.parent as IrClass).dump()}" }

        return innerClassConstructors.getOrPut(innerClassConstructor) {
            createInnerClassConstructorWithOuterThisParameter(innerClassConstructor)
        }.also {
            if (innerClassConstructor.isPrimary) {
                originalInnerClassPrimaryConstructorByClass[innerClass] = innerClassConstructor
            }
        }
    }

    override fun getInnerClassOriginalPrimaryConstructorOrNull(innerClass: IrClass): IrConstructor? {
        assert(innerClass.isInner) { "Class is not inner: $innerClass" }

        return originalInnerClassPrimaryConstructorByClass[innerClass]
    }

    private fun createInnerClassConstructorWithOuterThisParameter(oldConstructor: IrConstructor): IrConstructor =
        buildConstructor(oldConstructor.descriptor) {
            updateFrom(oldConstructor)
            returnType = oldConstructor.returnType
        }.apply {
            annotations = oldConstructor.annotations.map { it.deepCopyWithSymbols(this) }
            parent = oldConstructor.parent
            returnType = oldConstructor.returnType
            copyTypeParametersFrom(oldConstructor)

            val outerThisValueParameter = buildValueParameter(this) {
                origin = JvmLoweredDeclarationOrigin.FIELD_FOR_OUTER_THIS
                name = Name.identifier(AsmUtil.CAPTURED_THIS_FIELD)
                index = 0
                type = oldConstructor.parentAsClass.parentAsClass.defaultType
            }
            valueParameters = listOf(outerThisValueParameter) + oldConstructor.valueParameters.map { it.copyTo(this, index = it.index + 1) }
            metadata = oldConstructor.metadata
        }
}

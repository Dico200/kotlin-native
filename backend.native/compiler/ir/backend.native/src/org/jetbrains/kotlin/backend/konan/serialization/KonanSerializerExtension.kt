/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.konan.KonanProtoBuf
import org.jetbrains.kotlin.metadata.serialization.MutableVersionRequirementTable
import org.jetbrains.kotlin.serialization.KonanDescriptorSerializer
import org.jetbrains.kotlin.serialization.KotlinSerializerExtensionBase
import org.jetbrains.kotlin.serialization.konan.KonanSerializerProtocol
import org.jetbrains.kotlin.types.KotlinType

internal class KonanSerializerExtension(val context: Context, override val metadataVersion: BinaryVersion,  val declarationTable: DeclarationTable) :
        KotlinSerializerExtensionBase(KonanSerializerProtocol)/*, IrAwareExtension */ {

    //val inlineDescriptorTable = DescriptorTable(context.irBuiltIns)
    override val stringTable = KonanStringTable()
    override fun shouldUseTypeTable(): Boolean = true

    fun uniqId(descriptor: DeclarationDescriptor): KonanProtoBuf.DescriptorUniqId {
        if (declarationTable.descriptors[descriptor] == null) {
            println("### no declaration for descriptor: $descriptor")
        }
        val index = declarationTable.descriptors[descriptor] ?: -1
        return newDescriptorUniqId(index)
    }

    override fun serializeType(type: KotlinType, proto: ProtoBuf.Type.Builder) {
        // TODO: For debugging purpose we store the textual 
        // representation of serialized types.
        // To be removed for release 1.0.
        proto.setExtension(KonanProtoBuf.typeText, type.toString())

        super.serializeType(type, proto)
    }

    override fun serializeTypeParameter(typeParameter: TypeParameterDescriptor, proto: ProtoBuf.TypeParameter.Builder) {
        proto.setExtension(KonanProtoBuf.typeParamUniqId, uniqId(typeParameter))
        super.serializeTypeParameter(typeParameter, proto)
    }

    override fun serializeValueParameter(descriptor: ValueParameterDescriptor, proto: ProtoBuf.ValueParameter.Builder) {
        proto.setExtension(KonanProtoBuf.valueParamUniqId, uniqId(descriptor))
        super.serializeValueParameter(descriptor, proto)
    }

    override fun serializeEnumEntry(descriptor: ClassDescriptor, proto: ProtoBuf.EnumEntry.Builder) {
        proto.setExtension(KonanProtoBuf.enumEntryUniqId, uniqId(descriptor))
        // Serialization doesn't preserve enum entry order, so we need to serialize ordinal.
        val ordinal = context.specialDeclarationsFactory.getEnumEntryOrdinal(descriptor)
        proto.setExtension(KonanProtoBuf.enumEntryOrdinal, ordinal)
        super.serializeEnumEntry(descriptor, proto)
    }

    override fun serializeConstructor(descriptor: ConstructorDescriptor, proto: ProtoBuf.Constructor.Builder) {
        proto.setExtension(KonanProtoBuf.constructorUniqId, uniqId(descriptor))
        super.serializeConstructor(descriptor, proto)
    }

    override fun serializeClass(descriptor: ClassDescriptor, proto: ProtoBuf.Class.Builder, versionRequirementTable: MutableVersionRequirementTable) {
        proto.setExtension(KonanProtoBuf.classUniqId, uniqId(descriptor))
        super.serializeClass(descriptor, proto, versionRequirementTable)
    }

    override fun serializeFunction(descriptor: FunctionDescriptor, proto: ProtoBuf.Function.Builder) {
        proto.setExtension(KonanProtoBuf.functionUniqId, uniqId(descriptor))
        super.serializeFunction(descriptor, proto)
    }

    override fun serializeProperty(descriptor: PropertyDescriptor, proto: ProtoBuf.Property.Builder, versionRequirementTable: MutableVersionRequirementTable) {
        val variable = originalVariables[descriptor]
        if (variable != null) {
            proto.setExtension(KonanProtoBuf.usedAsVariable, true)
        }
        proto.setExtension(KonanProtoBuf.propertyUniqId, uniqId(descriptor))
        proto.setExtension(KonanProtoBuf.hasBackingField,
            context.ir.propertiesWithBackingFields.contains(descriptor))

        super.serializeProperty(descriptor, proto, versionRequirementTable)
    }

    override fun releaseCoroutines(): Boolean =
            context.config.configuration.languageVersionSettings.supportsFeature(LanguageFeature.ReleaseCoroutines)
/*
    override fun addFunctionIR(proto: ProtoBuf.Function.Builder, serializedIR: String) 
        = proto.setInlineIr(inlineBody(serializedIR))

    override fun addConstructorIR(proto: ProtoBuf.Constructor.Builder, serializedIR: String) 
        = proto.setConstructorIr(inlineBody(serializedIR))

    override fun addGetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String) 
        = proto.setGetterIr(inlineBody(serializedIR))

    override fun addSetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String) 
        = proto.setSetterIr(inlineBody(serializedIR))

    override fun serializeInlineBody(descriptor: FunctionDescriptor, serializer: KonanDescriptorSerializer): String {

        return IrSerializer( 
            context, inlineDescriptorTable, stringTable, serializer, descriptor).serializeInlineBody()
    }
    */
}
/*
object KonanSerializerProtocol : SerializerExtensionProtocol(
        ExtensionRegistryLite.newInstance().apply {
           KonanProtoBuf.registerAllExtensions(this)
        },
        KonanProtoBuf.packageFqName,
        KonanProtoBuf.constructorAnnotation,
        KonanProtoBuf.classAnnotation,
        KonanProtoBuf.functionAnnotation,
        KonanProtoBuf.propertyAnnotation,
        KonanProtoBuf.enumEntryAnnotation,
        KonanProtoBuf.compileTimeValue,
        KonanProtoBuf.parameterAnnotation,
        KonanProtoBuf.typeAnnotation,
        KonanProtoBuf.typeParameterAnnotation
)
*/
/*
>>>>>>> 7cad178e4... Whole module deserialization prototype.
internal interface IrAwareExtension {

    fun serializeInlineBody(descriptor: FunctionDescriptor, serializer: KonanDescriptorSerializer): String 

    fun addFunctionIR(proto: ProtoBuf.Function.Builder, serializedIR: String): ProtoBuf.Function.Builder

    fun addConstructorIR(proto: ProtoBuf.Constructor.Builder, serializedIR: String): ProtoBuf.Constructor.Builder

    fun addSetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String): ProtoBuf.Property.Builder

    fun addGetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String): ProtoBuf.Property.Builder
}
*/

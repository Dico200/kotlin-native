/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.serialization


import org.jetbrains.kotlin.protobuf.*
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.CodedInputStream.*
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.konan.ir.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.metadata.KonanIr
import org.jetbrains.kotlin.metadata.KonanIr.IrConst.ValueCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrDeclarator.DeclaratorCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrOperation.OperationCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrStatement.StatementCase
import org.jetbrains.kotlin.metadata.KonanIr.IrType.KindCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrTypeArgument.KindCase.*
import org.jetbrains.kotlin.metadata.KonanIr.IrVarargElement.VarargElementCase
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.ir.util.IrDeserializer
import org.jetbrains.kotlin.backend.common.WithLogger
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.serialization.konan.KonanSerializerProtocol

class IrModuleDeserialization(val logger: WithLogger, val builtIns: IrBuiltIns): IrDeserializer {

    var currentModule: ModuleDescriptor? = null

    private val loopIndex = mutableMapOf<Int, IrLoop>()

    val originIndex = (IrDeclarationOrigin::class.nestedClasses).map { it.objectInstance as IrDeclarationOriginImpl }.associateBy { it.name }

    val dummy = DummyDescriptors(builtIns)

    private fun deserializeTypeArguments(proto: KonanIr.TypeArguments): List<IrType> {
        logger.log{"### deserializeTypeArguments"}
        val result = mutableListOf<IrType>()
        proto.typeArgumentList.forEach { typeProto ->
            val type = deserializeIrType(typeProto)
            result.add(type)
            logger.log{"$type"}
        }
        return result
    }

    val deserializedSymbols = mutableMapOf<Long, IrSymbol>()
    val deserializedDeclarations = mutableMapOf<DeclarationDescriptor, IrDeclaration>()

    init {
        var currentIndex = 0L
        builtIns.knownBuiltins.forEach {
            deserializedSymbols.put(currentIndex, it.symbol)
            deserializedDeclarations.put(it.descriptor, it)
            currentIndex++
        }
    }

    fun deserializeDescriptorReference(proto: KonanIr.DescriptorReference): DeclarationDescriptor {
        val packageFqName = FqName(proto.packageFqName)
        val classFqName = FqName(proto.classFqName)
        val protoIndex = proto.uniqId.index


        val (clazz, members) = if (proto.classFqName == "") {
            Pair(null, currentModule!!.getPackage(packageFqName).memberScope.getContributedDescriptors())
        } else {
            val clazz = currentModule!!.findClassAcrossModuleDependencies(ClassId(packageFqName, classFqName, false))!!
            Pair(clazz, clazz.unsubstitutedMemberScope.getContributedDescriptors() + clazz.getConstructors())
        }

        if (proto.isEnumEntry) {
            val name = proto.name
            val clazz = clazz!! as DeserializedClassDescriptor
            val memberScope = clazz.getUnsubstitutedMemberScope()
            return memberScope.getContributedClassifier(Name.identifier(name), NoLookupLocation.FROM_BACKEND)!!
        }

        if (proto.isEnumSpecial) {
            val name = proto.name

            return clazz!!.getStaticScope().getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_BACKEND)!!.single()

        }

        members.forEach { member ->
            if (proto.isDefaultConstructor && member is ClassConstructorDescriptor) return member

            val realMembers = if (proto.isFakeOverride && member is CallableMemberDescriptor && member.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                member.resolveFakeOverrideMaybeAbstract()!!
            else
                setOf(member)

            val memberIndices = realMembers.map { it.getUniqId()?.index }.filterNotNull()

            if (memberIndices.contains(protoIndex)) {

                if (member is PropertyDescriptor) {
                    if (proto.isSetter) return member.setter!!// ?: return@forEach
                    if (proto.isGetter) return member.getter!!
                    return member
                } else {
                    return member
                }

            }
        }
        error("Could not find serialized descriptor for index: ${proto.uniqId.index.toString(16)} ")
    }

    fun deserializeIrSymbol(proto: KonanIr.IrSymbol): IrSymbol {
        val index = proto.uniqId.index

        val symbol = deserializedSymbols.getOrPut(index) {

            val descriptor = if (proto.hasDescriptorReference()) deserializeDescriptorReference(proto.descriptorReference) else null

            when (proto.kind) {
                KonanIr.IrSymbolKind.ANONYMOUS_INIT_SYMBOL ->
                    IrAnonymousInitializerSymbolImpl(descriptor as ClassDescriptor? ?: dummy.classDescriptor)
                KonanIr.IrSymbolKind.CLASS_SYMBOL ->
                    IrClassSymbolImpl(descriptor as ClassDescriptor? ?: dummy.classDescriptor)
                KonanIr.IrSymbolKind.CONSTRUCTOR_SYMBOL ->
                    IrConstructorSymbolImpl(descriptor as ClassConstructorDescriptor? ?: dummy.constructorDescriptor)
                KonanIr.IrSymbolKind.TYPE_PARAMETER_SYMBOL ->
                    IrTypeParameterSymbolImpl(descriptor as TypeParameterDescriptor? ?: dummy.typeParameterDescriptor)
                KonanIr.IrSymbolKind.ENUM_ENTRY_SYMBOL ->
                    IrEnumEntrySymbolImpl(descriptor as ClassDescriptor? ?: dummy.classDescriptor)
                KonanIr.IrSymbolKind.FIELD_SYMBOL ->
                    IrFieldSymbolImpl(descriptor as PropertyDescriptor? ?: dummy.propertyDescriptor)
                KonanIr.IrSymbolKind.FUNCTION_SYMBOL ->
                    IrSimpleFunctionSymbolImpl(descriptor as FunctionDescriptor? ?: dummy.functionDescriptor)
            //RETURN_TARGET ->
            //  IrReturnTargetSymbolImpl
                KonanIr.IrSymbolKind.VARIABLE_SYMBOL ->
                    IrVariableSymbolImpl(descriptor as VariableDescriptor? ?: dummy.variableDescriptor)
                KonanIr.IrSymbolKind.VALUE_PARAMETER_SYMBOL ->
                    IrValueParameterSymbolImpl(descriptor as ParameterDescriptor? ?: dummy.parameterDescriptor)
                else -> TODO("Unexpected classifier symbol kind: ${proto.kind}")
            }
        }
        return symbol

    }

    fun deserializeIrTypeVariance(variance: KonanIr.IrTypeVariance) = when(variance) {
        KonanIr.IrTypeVariance.IN -> Variance.IN_VARIANCE
        KonanIr.IrTypeVariance.OUT -> Variance.OUT_VARIANCE
        KonanIr.IrTypeVariance.INV -> Variance.INVARIANT
    }

    fun deserializeIrTypeArgument(proto: KonanIr.IrTypeArgument) = when (proto.kindCase) {
        STAR -> IrStarProjectionImpl
        TYPE -> makeTypeProjection(
                        deserializeIrType(proto.type.type), deserializeIrTypeVariance(proto.type.variance))
        else -> TODO("Unexpected projection kind")

    }

    fun deserializeAnnotations(annotations: KonanIr.Annotations): List<IrCall> {
        return annotations.annotationList.map {
            deserializeCall(it, 0, 0, builtIns.unitType) // TODO: need a proper deserialization here
        }
    }

    fun deserializeSimpleType(proto: KonanIr.IrSimpleType): IrSimpleType {
        val arguments = proto.argumentList.map { deserializeIrTypeArgument(it) }
        val annotations= deserializeAnnotations(proto.base.annotations)
        val symbol =  deserializeIrSymbol(proto.classifier) as IrClassifierSymbol
        logger.log { "deserializeSimpleType: symbol=$symbol" }
        val result =  IrSimpleTypeImpl(
                null,
                symbol,
                proto.hasQuestionMark,
                arguments,
                annotations
        )
        logger.log { "ir_type = $result; render = ${result.render()}"}
        return result
    }

    fun deserializeDynamicType(proto: KonanIr.IrDynamicType): IrDynamicType {
        val annotations= deserializeAnnotations(proto.base.annotations)
        val variance = deserializeIrTypeVariance(proto.base.variance)
        return IrDynamicTypeImpl(null, annotations, variance)
    }

    fun deserializeErrorType(proto: KonanIr.IrErrorType): IrErrorType {
        val annotations= deserializeAnnotations(proto.base.annotations)
        val variance = deserializeIrTypeVariance(proto.base.variance)
        return IrErrorTypeImpl(null, annotations, variance)
    }

    fun deserializeIrType(proto: KonanIr.IrType): IrType {
        return when (proto.kindCase) {
            SIMPLE -> deserializeSimpleType(proto.simple)
            DYNAMIC -> deserializeDynamicType(proto.dynamic)
            ERROR -> deserializeErrorType(proto.error)
            else -> TODO("Unexpected IrType kind: ${proto.kindCase}")
        }
    }

    /* -------------------------------------------------------------- */

    private fun deserializeBlockBody(proto: KonanIr.IrBlockBody,
                                     start: Int, end: Int): IrBlockBody {

        val statements = mutableListOf<IrStatement>()

        val statementProtos = proto.statementList
        statementProtos.forEach {
            statements.add(deserializeStatement(it) as IrStatement)
        }

        return IrBlockBodyImpl(start, end, statements)
    }

    private fun deserializeBranch(proto: KonanIr.IrBranch, start: Int, end: Int): IrBranch {

        val condition = deserializeExpression(proto.condition)
        val result = deserializeExpression(proto.result)

        return IrBranchImpl(start, end, condition, result)
    }

    private fun deserializeCatch(proto: KonanIr.IrCatch, start: Int, end: Int): IrCatch {
        val catchParameter = deserializeDeclaration(proto.catchParameter, null) as IrVariable // TODO: we need a proper parent here
        val result = deserializeExpression(proto.result)

        val catch = IrCatchImpl(start, end, catchParameter, result)
        return catch
    }

    private fun deserializeSyntheticBody(proto: KonanIr.IrSyntheticBody, start: Int, end: Int): IrSyntheticBody {
        val kind = when (proto.kind) {
            KonanIr.IrSyntheticBodyKind.ENUM_VALUES -> IrSyntheticBodyKind.ENUM_VALUES
            KonanIr.IrSyntheticBodyKind.ENUM_VALUEOF -> IrSyntheticBodyKind.ENUM_VALUEOF
        }
        return IrSyntheticBodyImpl(start, end, kind)
    }

    private fun deserializeStatement(proto: KonanIr.IrStatement): IrElement {
        val start = proto.coordinates.startOffset
        val end = proto.coordinates.endOffset
        val element = when (proto.statementCase) {
            StatementCase.BLOCK_BODY //proto.hasBlockBody()
                -> deserializeBlockBody(proto.blockBody, start, end)
            StatementCase.BRANCH //proto.hasBranch()
                -> deserializeBranch(proto.branch, start, end)
            StatementCase.CATCH //proto.hasCatch()
                -> deserializeCatch(proto.catch, start, end)
            StatementCase.DECLARATION // proto.hasDeclaration()
                -> deserializeDeclaration(proto.declaration, null) // TODO: we need a proper parent here.
            StatementCase.EXPRESSION // proto.hasExpression()
                -> deserializeExpression(proto.expression)
            StatementCase.SYNTHETIC_BODY // proto.hasSyntheticBody()
                -> deserializeSyntheticBody(proto.syntheticBody, start, end)
            else
                -> TODO("Statement deserialization not implemented: ${proto.statementCase}")
        }

        logger.log{"### Deserialized statement: ${ir2string(element)}"}

        return element
    }

    private fun deserializeBlock(proto: KonanIr.IrBlock, start: Int, end: Int, type: IrType): IrBlock {
        val statements = mutableListOf<IrStatement>()
        val statementProtos = proto.statementList
        statementProtos.forEach {
            statements.add(deserializeStatement(it) as IrStatement)
        }

        val isLambdaOrigin = if (proto.isLambdaOrigin) IrStatementOrigin.LAMBDA else null

        return IrBlockImpl(start, end, type, isLambdaOrigin, statements)
    }

    private fun deserializeMemberAccessCommon(access: IrMemberAccessExpression, proto: KonanIr.MemberAccessCommon) {

        proto.valueArgumentList.mapIndexed { i, arg ->
            if (arg.hasExpression()) {
                val expr = deserializeExpression(arg.expression)
                access.putValueArgument(i, expr)
            }
        }

        deserializeTypeArguments(proto.typeArguments).forEachIndexed { index, type ->
            access.putTypeArgument(index, type)
        }

        if (proto.hasDispatchReceiver()) {
            access.dispatchReceiver = deserializeExpression(proto.dispatchReceiver)
        }
        if (proto.hasExtensionReceiver()) {
            access.extensionReceiver = deserializeExpression(proto.extensionReceiver)
        }
    }

    private fun deserializeClassReference(proto: KonanIr.IrClassReference, start: Int, end: Int, type: IrType): IrClassReference {
        val symbol = deserializeIrSymbol(proto.classSymbol) as IrClassifierSymbol
        val classType = deserializeIrType(proto.type)
        /** TODO: [createClassifierSymbolForClassReference] is internal function */
        return IrClassReferenceImpl(start, end, type, symbol, classType)
    }

    private fun deserializeCall(proto: KonanIr.IrCall, start: Int, end: Int, type: IrType): IrCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrFunctionSymbol

        val superSymbol = if (proto.hasSuper()) {
            deserializeIrSymbol(proto.`super`) as IrClassSymbol
        } else null

        val call: IrCall = when (proto.kind) {
            KonanIr.IrCall.Primitive.NOT_PRIMITIVE ->
                // TODO: implement the last three args here.
                IrCallImpl(start, end, type,
                            symbol, symbol.descriptor,
                            proto.memberAccess.valueArgumentList.size,
                            proto.memberAccess.typeArguments.typeArgumentCount,
                        null, superSymbol)
            KonanIr.IrCall.Primitive.NULLARY ->
                IrNullaryPrimitiveImpl(start, end, type, null, symbol)
            KonanIr.IrCall.Primitive.UNARY ->
                IrUnaryPrimitiveImpl(start, end, type, null, symbol)
            KonanIr.IrCall.Primitive.BINARY ->
                IrBinaryPrimitiveImpl(start, end, type, null, symbol)
            else -> TODO("Unexpected primitive IrCall.")
        }
        deserializeMemberAccessCommon(call, proto.memberAccess)
        return call
    }

    private fun deserializeComposite(proto: KonanIr.IrComposite, start: Int, end: Int, type: IrType): IrComposite {
        val statements = mutableListOf<IrStatement>()
        val statementProtos = proto.statementList
        statementProtos.forEach {
            statements.add(deserializeStatement(it) as IrStatement)
        }
        return IrCompositeImpl(start, end, type, null, statements)
    }

    private fun deserializeDelegatingConstructorCall(proto: KonanIr.IrDelegatingConstructorCall, start: Int, end: Int): IrDelegatingConstructorCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrConstructorSymbol
        val call = IrDelegatingConstructorCallImpl(start, end, builtIns.unitType, symbol, symbol.descriptor, proto.memberAccess.typeArguments.typeArgumentCount)

        deserializeMemberAccessCommon(call, proto.memberAccess)
        return call
    }



    fun deserializeEnumConstructorCall(proto: KonanIr.IrEnumConstructorCall, start: Int, end: Int, type: IrType): IrEnumConstructorCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrConstructorSymbol
        return IrEnumConstructorCallImpl(start, end, type, symbol, proto.memberAccess.typeArguments.typeArgumentList.size, proto.memberAccess.valueArgumentList.size)
    }



    private fun deserializeFunctionReference(proto: KonanIr.IrFunctionReference,
                                             start: Int, end: Int, type: IrType): IrFunctionReference {

        val symbol = deserializeIrSymbol(proto.symbol) as IrFunctionSymbol
        val callable= IrFunctionReferenceImpl(start, end, type, symbol, symbol.descriptor, proto.typeArguments.typeArgumentCount, null)

        deserializeTypeArguments(proto.typeArguments).forEachIndexed { index, argType ->
            callable.putTypeArgument(index, argType)
        }
        return callable
    }

    private fun deserializeGetClass(proto: KonanIr.IrGetClass, start: Int, end: Int, type: IrType): IrGetClass {
        val argument = deserializeExpression(proto.argument)
        return IrGetClassImpl(start, end, type, argument)
    }

    private fun deserializeGetField(proto: KonanIr.IrGetField, start: Int, end: Int): IrGetField {
        val access = proto.fieldAccess
        val symbol = deserializeIrSymbol(access.symbol) as IrFieldSymbol
        val type = deserializeIrType(proto.type)
        val superQualifier = if (access.hasSuper()) {
            deserializeIrSymbol(access.symbol) as IrClassSymbol
        } else null
        val receiver = if (access.hasReceiver()) {
            deserializeExpression(access.receiver)
        } else null

        return IrGetFieldImpl(start, end, symbol, type, receiver, null, superQualifier)
    }

    private fun deserializeGetValue(proto: KonanIr.IrGetValue, start: Int, end: Int): IrGetValue {
        val symbol = deserializeIrSymbol(proto.symbol) as IrValueSymbol
        val type = deserializeIrType(proto.type)

        // TODO: origin!
        return IrGetValueImpl(start, end, type, symbol, null)
    }

    private fun deserializeGetEnumValue(proto: KonanIr.IrGetEnumValue, start: Int, end: Int): IrGetEnumValue {
        val type = deserializeIrType(proto.type)
        val symbol = deserializeIrSymbol(proto.symbol) as IrEnumEntrySymbol

        return IrGetEnumValueImpl(start, end, type, symbol)
    }

    private fun deserializeGetObject(proto: KonanIr.IrGetObject, start: Int, end: Int, type: IrType): IrGetObjectValue {
        val symbol = deserializeIrSymbol(proto.symbol) as IrClassSymbol
        return IrGetObjectValueImpl(start, end, type, symbol)
    }

    private fun deserializeInstanceInitializerCall(proto: KonanIr.IrInstanceInitializerCall, start: Int, end: Int): IrInstanceInitializerCall {
        val symbol = deserializeIrSymbol(proto.symbol) as IrClassSymbol
        return IrInstanceInitializerCallImpl(start, end, symbol, builtIns.unitType)
    }

    private fun deserializePropertyReference(proto: KonanIr.IrPropertyReference,
                                             start: Int, end: Int, type: IrType): IrPropertyReference {

        val field = if (proto.hasField()) deserializeIrSymbol(proto.field) as IrFieldSymbol else null
        val getter = if (proto.hasGetter()) deserializeIrSymbol(proto.getter) as IrFunctionSymbol else null
        val setter = if (proto.hasSetter()) deserializeIrSymbol(proto.setter) as IrFunctionSymbol else null
        //val descriptor = declarationTable.valueByIndex(proto.declaration.index)!!.descriptor as PropertyDescriptor

        val callable= IrPropertyReferenceImpl(start, end, type,
                dummy.propertyDescriptor,
                proto.typeArguments.typeArgumentCount,
                field,
                getter,
                setter,
                null)

        deserializeTypeArguments(proto.typeArguments).forEachIndexed { index, argType ->
            callable.putTypeArgument(index, argType)
        }
        return callable
    }

    private fun deserializeReturn(proto: KonanIr.IrReturn, start: Int, end: Int, type: IrType): IrReturn {
        val symbol = deserializeIrSymbol(proto.returnTarget) as IrReturnTargetSymbol
        val value = deserializeExpression(proto.value)
        return IrReturnImpl(start, end, builtIns.nothingType, symbol, value)
    }

    private fun deserializeSetField(proto: KonanIr.IrSetField, start: Int, end: Int): IrSetField {
        val access = proto.fieldAccess
        val symbol = deserializeIrSymbol(access.symbol) as IrFieldSymbol
        val superQualifier = if (access.hasSuper()) {
            deserializeIrSymbol(access.symbol) as IrClassSymbol
        } else null
        val receiver = if (access.hasReceiver()) {
            deserializeExpression(access.receiver)
        } else null
        val value = deserializeExpression(proto.value)

        return IrSetFieldImpl(start, end, symbol, receiver, value, builtIns.unitType, null, superQualifier)
    }

    private fun deserializeSetVariable(proto: KonanIr.IrSetVariable, start: Int, end: Int): IrSetVariable {
        val symbol = deserializeIrSymbol(proto.symbol) as IrVariableSymbol
        val value = deserializeExpression(proto.value)
        return IrSetVariableImpl(start, end, builtIns.unitType, symbol, value, null)
    }

    private fun deserializeSpreadElement(proto: KonanIr.IrSpreadElement): IrSpreadElement {
        val expression = deserializeExpression(proto.expression)
        return IrSpreadElementImpl(proto.coordinates.startOffset, proto.coordinates.endOffset, expression)
    }

    private fun deserializeStringConcat(proto: KonanIr.IrStringConcat, start: Int, end: Int, type: IrType): IrStringConcatenation {
        val argumentProtos = proto.argumentList
        val arguments = mutableListOf<IrExpression>()

        argumentProtos.forEach {
            arguments.add(deserializeExpression(it))
        }
        return IrStringConcatenationImpl(start, end, type, arguments)
    }

    private fun deserializeThrow(proto: KonanIr.IrThrow, start: Int, end: Int, type: IrType): IrThrowImpl {
        return IrThrowImpl(start, end, builtIns.nothingType, deserializeExpression(proto.value))
    }

    private fun deserializeTry(proto: KonanIr.IrTry, start: Int, end: Int, type: IrType): IrTryImpl {
        val result = deserializeExpression(proto.result)
        val catches = mutableListOf<IrCatch>()
        proto.catchList.forEach {
            catches.add(deserializeStatement(it) as IrCatch) 
        }
        val finallyExpression = 
            if (proto.hasFinally()) deserializeExpression(proto.getFinally()) else null
        return IrTryImpl(start, end, type, result, catches, finallyExpression)
    }

    private fun deserializeTypeOperator(operator: KonanIr.IrTypeOperator): IrTypeOperator {
        when (operator) {
            KonanIr.IrTypeOperator.CAST
                -> return IrTypeOperator.CAST
            KonanIr.IrTypeOperator.IMPLICIT_CAST
                -> return IrTypeOperator.IMPLICIT_CAST
            KonanIr.IrTypeOperator.IMPLICIT_NOTNULL
                -> return IrTypeOperator.IMPLICIT_NOTNULL
            KonanIr.IrTypeOperator.IMPLICIT_COERCION_TO_UNIT
                -> return IrTypeOperator.IMPLICIT_COERCION_TO_UNIT
            KonanIr.IrTypeOperator.IMPLICIT_INTEGER_COERCION
                -> return IrTypeOperator.IMPLICIT_INTEGER_COERCION
            KonanIr.IrTypeOperator.SAFE_CAST
                -> return IrTypeOperator.SAFE_CAST
            KonanIr.IrTypeOperator.INSTANCEOF
                -> return IrTypeOperator.INSTANCEOF
            KonanIr.IrTypeOperator.NOT_INSTANCEOF
                -> return IrTypeOperator.NOT_INSTANCEOF
        }
    }

    private fun deserializeTypeOp(proto: KonanIr.IrTypeOp, start: Int, end: Int, type: IrType) : IrTypeOperatorCall {
        val operator = deserializeTypeOperator(proto.operator)
        val operand = deserializeIrType(proto.operand)//.brokenIr
        val argument = deserializeExpression(proto.argument)
        return IrTypeOperatorCallImpl(start, end, type, operator, operand).apply {
            this.argument = argument
            this.typeOperandClassifier = operand.classifierOrFail
        }
    }

    private fun deserializeVararg(proto: KonanIr.IrVararg, start: Int, end: Int, type: IrType): IrVararg {
        val elementType = deserializeIrType(proto.elementType)

        val elements = mutableListOf<IrVarargElement>()
        proto.elementList.forEach {
            elements.add(deserializeVarargElement(it))
        }
        return IrVarargImpl(start, end, type, elementType, elements)
    }

    private fun deserializeVarargElement(element: KonanIr.IrVarargElement): IrVarargElement {
        return when (element.varargElementCase) {
            VarargElementCase.EXPRESSION
                -> deserializeExpression(element.expression)
            VarargElementCase.SPREAD_ELEMENT
                -> deserializeSpreadElement(element.spreadElement)
            else 
                -> TODO("Unexpected vararg element")
        }
    }

    private fun deserializeWhen(proto: KonanIr.IrWhen, start: Int, end: Int, type: IrType): IrWhen {
        val branches = mutableListOf<IrBranch>()

        proto.branchList.forEach {
            branches.add(deserializeStatement(it) as IrBranch)
        }

        // TODO: provide some origin!
        return  IrWhenImpl(start, end, type, null, branches)
    }

    private fun deserializeLoop(proto: KonanIr.Loop, loop: IrLoopBase): IrLoopBase {
        val loopId = proto.loopId
        loopIndex.getOrPut(loopId){loop}

        val label = if (proto.hasLabel()) proto.label else null
        val body = if (proto.hasBody()) deserializeExpression(proto.body) else null
        val condition = deserializeExpression(proto.condition)

        loop.label = label
        loop.condition = condition
        loop.body = body

        return loop
    }

    private fun deserializeDoWhile(proto: KonanIr.IrDoWhile, start: Int, end: Int, type: IrType): IrDoWhileLoop {
        // we create the loop before deserializing the body, so that 
        // IrBreak statements have something to put into 'loop' field.
        val loop = IrDoWhileLoopImpl(start, end, type, null)
        deserializeLoop(proto.loop, loop)
        return loop
    }

    private fun deserializeWhile(proto: KonanIr.IrWhile, start: Int, end: Int, type: IrType): IrWhileLoop {
        // we create the loop before deserializing the body, so that 
        // IrBreak statements have something to put into 'loop' field.
        val loop = IrWhileLoopImpl(start, end, type, null)
        deserializeLoop(proto.loop, loop)
        return loop
    }

    private fun deserializeBreak(proto: KonanIr.IrBreak, start: Int, end: Int, type: IrType): IrBreak {
        val label = if(proto.hasLabel()) proto.label else null
        val loopId = proto.loopId
        val loop = loopIndex[loopId]!!
        val irBreak = IrBreakImpl(start, end, type, loop)
        irBreak.label = label

        return irBreak
    }

    private fun deserializeContinue(proto: KonanIr.IrContinue, start: Int, end: Int, type: IrType): IrContinue {
        val label = if(proto.hasLabel()) proto.label else null
        val loopId = proto.loopId
        val loop = loopIndex[loopId]!!
        val irContinue = IrContinueImpl(start, end, type, loop)
        irContinue.label = label

        return irContinue
    }

    private fun deserializeConst(proto: KonanIr.IrConst, start: Int, end: Int, type: IrType): IrExpression =
        when(proto.valueCase) {
            NULL
                -> IrConstImpl.constNull(start, end, type)
            BOOLEAN
                -> IrConstImpl.boolean(start, end, type, proto.boolean)
            BYTE
                -> IrConstImpl.byte(start, end, type, proto.byte.toByte())
            CHAR
                -> IrConstImpl.char(start, end, type, proto.char.toChar())
            SHORT
                -> IrConstImpl.short(start, end, type, proto.short.toShort())
            INT
                -> IrConstImpl.int(start, end, type, proto.int)
            LONG
                -> IrConstImpl.long(start, end, type, proto.long)
            STRING
                -> IrConstImpl.string(start, end, type, proto.string)
            FLOAT
                -> IrConstImpl.float(start, end, type, proto.float)
            DOUBLE
                -> IrConstImpl.double(start, end, type, proto.double)
            VALUE_NOT_SET
                -> error("Const deserialization error: ${proto.valueCase} ")
        }

    private fun deserializeOperation(proto: KonanIr.IrOperation, start: Int, end: Int, type: IrType): IrExpression =
        when (proto.operationCase) {
            BLOCK
                -> deserializeBlock(proto.block, start, end, type)
            BREAK
                -> deserializeBreak(proto.`break`, start, end, type)
            CLASS_REFERENCE
                -> deserializeClassReference(proto.classReference, start, end, type)
            CALL
                -> deserializeCall(proto.call, start, end, type)
            COMPOSITE
                -> deserializeComposite(proto.composite, start, end, type)
            CONST
                -> deserializeConst(proto.const, start, end, type)
            CONTINUE
                -> deserializeContinue(proto.`continue`, start, end, type)
            DELEGATING_CONSTRUCTOR_CALL
                -> deserializeDelegatingConstructorCall(proto.delegatingConstructorCall, start, end)
            DO_WHILE
                -> deserializeDoWhile(proto.doWhile, start, end, type)
            ENUM_CONSTRUCTOR_CALL
                -> deserializeEnumConstructorCall(proto.enumConstructorCall, start, end, type)
            FUNCTION_REFERENCE
                -> deserializeFunctionReference(proto.functionReference, start, end, type)
            GET_ENUM_VALUE
                -> deserializeGetEnumValue(proto.getEnumValue, start, end)
            GET_CLASS
                -> deserializeGetClass(proto.getClass, start, end, type)
            GET_FIELD
                -> deserializeGetField(proto.getField, start, end)
            GET_OBJECT
                -> deserializeGetObject(proto.getObject, start, end, type)
            GET_VALUE
                -> deserializeGetValue(proto.getValue, start, end)
            INSTANCE_INITIALIZER_CALL
                -> deserializeInstanceInitializerCall(proto.instanceInitializerCall, start, end)
            PROPERTY_REFERENCE
                -> deserializePropertyReference(proto.propertyReference, start, end, type)
            RETURN
                -> deserializeReturn(proto.`return`, start, end, type)
            SET_FIELD
                -> deserializeSetField(proto.setField, start, end)
            SET_VARIABLE
                -> deserializeSetVariable(proto.setVariable, start, end)
            STRING_CONCAT
                -> deserializeStringConcat(proto.stringConcat, start, end, type)
            THROW
                -> deserializeThrow(proto.`throw`, start, end, type)
            TRY
                -> deserializeTry(proto.`try`, start, end, type)
            TYPE_OP
                -> deserializeTypeOp(proto.typeOp, start, end, type)
            VARARG
                -> deserializeVararg(proto.vararg, start, end, type)
            WHEN
                -> deserializeWhen(proto.`when`, start, end, type)
            WHILE
                -> deserializeWhile(proto.`while`, start, end, type)
            OPERATION_NOT_SET
                -> error("Expression deserialization not implemented: ${proto.operationCase}")
        }

    private fun deserializeExpression(proto: KonanIr.IrExpression): IrExpression {
        val start = proto.coordinates.startOffset
        val end = proto.coordinates.endOffset
        val type = deserializeIrType(proto.type)
        val operation = proto.operation
        val expression = deserializeOperation(operation, start, end, type)

        logger.log{"### Deserialized expression: ${ir2string(expression)} ir_type=$type"}
        return expression
    }

    private fun deserializeIrTypeParameter(proto: KonanIr.IrTypeParameter, start: Int, end: Int, origin: IrDeclarationOrigin): IrTypeParameter {
        val symbol = deserializeIrSymbol(proto.symbol) as IrTypeParameterSymbol
        val name = Name.identifier(proto.name)
        val variance = deserializeIrTypeVariance(proto.variance)
        val parameter = IrTypeParameterImpl(start, end, origin, symbol, name, proto.index, variance)

        val superTypes = proto.superTypeList.map {deserializeIrType(it)}
        parameter.superTypes.addAll(superTypes)
        return parameter
    }

    private fun deserializeIrTypeParameterContainer(proto: KonanIr.IrTypeParameterContainer, start: Int, end: Int, origin: IrDeclarationOrigin): List<IrTypeParameter> {
        return proto.typeParameterList.map { deserializeIrTypeParameter(it, start, end, origin) } // TODO: we need proper start, end and origin here?
    }

    private fun deserializeClassKind(kind: KonanIr.ClassKind) = when (kind) {
        KonanIr.ClassKind.CLASS -> ClassKind.CLASS
        KonanIr.ClassKind.INTERFACE -> ClassKind.INTERFACE
        KonanIr.ClassKind.ENUM_CLASS -> ClassKind.ENUM_CLASS
        KonanIr.ClassKind.ENUM_ENTRY -> ClassKind.ENUM_ENTRY
        KonanIr.ClassKind.ANNOTATION_CLASS -> ClassKind.ANNOTATION_CLASS
        KonanIr.ClassKind.OBJECT -> ClassKind.OBJECT
    }

    private fun deserializeIrValueParameter(proto: KonanIr.IrValueParameter, start: Int, end: Int, origin: IrDeclarationOrigin): IrValueParameter {

        val varargElementType = if (proto.hasVarargElementType()) deserializeIrType(proto.varargElementType) else null

        val parameter = IrValueParameterImpl(start, end, origin,
            deserializeIrSymbol(proto.symbol) as IrValueParameterSymbol,
            Name.identifier(proto.name),
            proto.index,
            deserializeIrType(proto.type),
            varargElementType,
            proto.isCrossinline,
            proto.isNoinline)

        parameter.defaultValue = if (proto.hasDefaultValue()) {
            val expression = deserializeExpression(proto.defaultValue)
            IrExpressionBodyImpl(expression)
        } else null

        return parameter
    }

    private fun deserializeIrClass(proto: KonanIr.IrClass, start: Int, end: Int, origin: IrDeclarationOrigin): IrClass {

        val symbol = deserializeIrSymbol(proto.symbol) as IrClassSymbol

        val clazz = IrClassImpl(start, end, origin,
                symbol,
                Name.identifier(proto.name),
                deserializeClassKind(proto.kind),
                deserializeVisibility(proto.visibility),
                deserializeModality(proto.modality),
                proto.isCompanion,
                proto.isInner,
                proto.isData,
                proto.isExternal,
                proto.isInline)

        proto.declarationContainer.declarationList.forEach {
            val member = deserializeDeclaration(it, clazz)
            clazz.addMember(member)
            member.parent = clazz
        }

        clazz.thisReceiver = deserializeIrValueParameter(proto.thisReceiver, start, end, origin) // TODO: we need proper start, end and origin here?

        val typeParameters = deserializeIrTypeParameterContainer (proto.typeParameters, start, end, origin) // TODO: we need proper start, end and origin here?
        clazz.typeParameters.addAll(typeParameters)

        val superTypes = proto.superTypeList.map { deserializeIrType(it) }
        clazz.superTypes.addAll(superTypes)

        //val symbolTable = context.ir.symbols.symbolTable
        //clazz.createParameterDeclarations(symbolTable)
        //clazz.addFakeOverrides(symbolTable)
        //clazz.setSuperSymbols(symbolTable)

        return clazz
    }

    private fun deserializeIrFunctionBase(base: KonanIr.IrFunctionBase, function: IrFunctionBase, start: Int, end: Int, origin: IrDeclarationOrigin) {

        function.returnType = deserializeIrType(base.returnType)
        function.body = if (base.hasBody()) deserializeStatement(base.body) as IrBody else null

        val valueParameters = base.valueParameterList.map { deserializeIrValueParameter(it, start, end, origin) } // TODO
        function.valueParameters.addAll(valueParameters)
        function.dispatchReceiverParameter = if (base.hasDispatchReceiver()) deserializeIrValueParameter(base.dispatchReceiver, start, end, origin) else null // TODO
        function.extensionReceiverParameter = if (base.hasExtensionReceiver()) deserializeIrValueParameter(base.extensionReceiver, start, end, origin) else null // TODO
        val typeParameters = deserializeIrTypeParameterContainer(base.typeParameters, start, end, origin) // TODO
        function.typeParameters.addAll(typeParameters)
    }

    private fun deserializeIrFunction(proto: KonanIr.IrFunction,
                                      start: Int, end: Int, origin: IrDeclarationOrigin, correspondingProperty: IrProperty? = null): IrSimpleFunction {

        logger.log{"### deserializing IrFunction ${proto.base.name}"}
        val symbol = deserializeIrSymbol(proto.symbol) as IrSimpleFunctionSymbol
        val function = IrFunctionImpl(start, end, origin, symbol,
                Name.identifier(proto.base.name),
                deserializeVisibility(proto.base.visibility),
                deserializeModality(proto.modality),
                proto.base.isInline,
                proto.base.isExternal,
                proto.isTailrec,
                proto.isSuspend)

        deserializeIrFunctionBase(proto.base, function, start, end, origin)
        val overridden = proto.overriddenList.map { deserializeIrSymbol(it) as IrSimpleFunctionSymbol }
        function.overriddenSymbols.addAll(overridden)

        function.correspondingProperty = correspondingProperty

//        function.createParameterDeclarations(symbolTable)
//        function.setOverrides(symbolTable)

        return function
    }

    private fun deserializeIrVariable(proto: KonanIr.IrVariable,
                                      start: Int, end: Int, origin: IrDeclarationOrigin): IrVariable {

        val initializer = if (proto.hasInitializer()) {
            deserializeExpression(proto.initializer)
        } else null

        val symbol = deserializeIrSymbol(proto.symbol) as IrVariableSymbol
        val type = deserializeIrType(proto.type)

        val variable = IrVariableImpl(start, end, origin, symbol, Name.identifier(proto.name), type, proto.isVar, proto.isConst, proto.isLateinit)
        variable.initializer = initializer
        return variable
    }

    private fun deserializeIrEnumEntry(proto: KonanIr.IrEnumEntry,
                                       start: Int, end: Int, origin: IrDeclarationOrigin): IrEnumEntry {
        val symbol = deserializeIrSymbol(proto.symbol) as IrEnumEntrySymbol

        val enumEntry = IrEnumEntryImpl(start, end, origin, symbol, Name.identifier(proto.name))
        if (proto.hasCorrespondingClass()) {
            enumEntry.correspondingClass = deserializeDeclaration(proto.correspondingClass, null) as IrClass
        }
        if (proto.hasInitializer()) {
            enumEntry.initializerExpression = deserializeExpression(proto.initializer)
        }

        return enumEntry
    }

    private fun deserializeIrAnonymousInit(proto: KonanIr.IrAnonymousInit, start: Int, end: Int, origin: IrDeclarationOrigin): IrAnonymousInitializer {
        val symbol = deserializeIrSymbol(proto.symbol) as IrAnonymousInitializerSymbol
        val initializer = IrAnonymousInitializerImpl(start, end, origin, symbol)
            initializer.body = deserializeBlockBody(proto.body.blockBody, start, end)
        return initializer
    }

    private fun deserializeVisibility(value: String): Visibility {
        return Visibilities.DEFAULT_VISIBILITY // TODO: fixme
    }

    private fun deserializeIrConstructor(proto: KonanIr.IrConstructor, start: Int, end: Int, origin: IrDeclarationOrigin): IrConstructor {
        val symbol = deserializeIrSymbol(proto.symbol) as IrConstructorSymbol
        val constructor = IrConstructorImpl(start, end, origin,
            symbol,
            Name.identifier(proto.base.name),
            deserializeVisibility(proto.base.visibility),
            proto.base.isInline,
            proto.base.isExternal,
            proto.isPrimary
        )

        deserializeIrFunctionBase(proto.base, constructor, start, end, origin)
        return constructor
    }

    private fun deserializeIrField(proto: KonanIr.IrField, start: Int, end: Int, origin: IrDeclarationOrigin): IrField {
        val symbol = deserializeIrSymbol(proto.symbol) as IrFieldSymbol
        val field = IrFieldImpl(start, end, origin,
            symbol,
            Name.identifier(proto.name),
            deserializeIrType(proto.type),
            deserializeVisibility(proto.visibility),
            proto.isFinal,
            proto.isExternal,
            proto.isStatic)
        val initializer = if (proto.hasInitializer()) deserializeExpression(proto.initializer) else null
        field.initializer = initializer?.let { IrExpressionBodyImpl(it) }

        return field
    }

    private fun deserializeModality(modality: KonanIr.ModalityKind) = when(modality) {
        KonanIr.ModalityKind.OPEN_MODALITY -> Modality.OPEN
        KonanIr.ModalityKind.SEALED_MODALITY -> Modality.SEALED
        KonanIr.ModalityKind.FINAL_MODALITY -> Modality.FINAL
        KonanIr.ModalityKind.ABSTRACT_MODALITY -> Modality.ABSTRACT
    }

    private fun deserializeIrProperty(proto: KonanIr.IrProperty, start: Int, end: Int, origin: IrDeclarationOrigin): IrProperty {

        val backingField = if (proto.hasBackingField()) deserializeIrField(proto.backingField, start, end, origin) else null

        val descriptor = dummy.propertyDescriptor //declarationTable.valueByIndex(proto.declaration.index)!!.descriptor as PropertyDescriptor

        val property = IrPropertyImpl(start, end, origin,
                descriptor,
                Name.identifier(proto.name),
                deserializeVisibility(proto.visibility),
                deserializeModality(proto.modality),
                proto.isVar,
                proto.isConst,
                proto.isLateinit,
                proto.isDelegated,
                proto.isExternal)

        property.backingField = backingField
        property.getter = if (proto.hasGetter()) deserializeIrFunction(proto.getter, start, end, origin, property) else null
        property.setter = if (proto.hasSetter()) deserializeIrFunction(proto.setter, start, end, origin, property) else null

        property.getter ?. let {deserializedDeclarations.put(it.descriptor, it)}
        property.setter ?. let {deserializedDeclarations.put(it.descriptor, it)}

        return property
    }

    private fun deserializeIrTypeAlias(proto: KonanIr.IrTypeAlias, start: Int, end: Int, origin: IrDeclarationOrigin): IrDeclaration { 
        return IrErrorDeclarationImpl(start, end, dummy.classDescriptor)
    }

    override fun findDeserializedDeclaration(descriptor: DeclarationDescriptor): IrDeclaration {

        println("### deserializeDescriptor descriptor = $descriptor ${descriptor.name}")

        val declaration = deserializedDeclarations[descriptor] ?:
            error("Unknown declaration descriptor: $descriptor")

        return declaration
    }

    private fun deserializeDeclaration(proto: KonanIr.IrDeclaration, parent: IrDeclarationParent?): IrDeclaration {

        val start = proto.coordinates.startOffset
        val end = proto.coordinates.endOffset
        val origin = originIndex[proto.origin.name]!!

        val declarator = proto.declarator

        val declaration: IrDeclaration = when (declarator.declaratorCase){
            IR_TYPE_ALIAS
                -> deserializeIrTypeAlias(declarator.irTypeAlias, start, end, origin)
            IR_ANONYMOUS_INIT
                -> deserializeIrAnonymousInit(declarator.irAnonymousInit, start, end, origin)
            IR_CONSTRUCTOR
                -> deserializeIrConstructor(declarator.irConstructor, start, end, origin)
            IR_FIELD
                -> deserializeIrField(declarator.irField, start, end, origin)
            IR_CLASS
                -> deserializeIrClass(declarator.irClass, start, end, origin)
            IR_FUNCTION
                -> deserializeIrFunction(declarator.irFunction, start, end, origin)
            IR_PROPERTY
                -> deserializeIrProperty(declarator.irProperty, start, end, origin)
            IR_VARIABLE
                -> deserializeIrVariable(declarator.irVariable, start, end, origin)
            IR_ENUM_ENTRY
                -> deserializeIrEnumEntry(declarator.irEnumEntry, start, end, origin)
            DECLARATOR_NOT_SET
                -> error("Declaration deserialization not implemented: ${declarator.declaratorCase}")
        }

        val annotations = deserializeAnnotations(proto.annotations)
        declaration.annotations.addAll(annotations)

        val sourceFileName = proto.fileName

        deserializedDeclarations.put(declaration.descriptor, declaration)
        logger.log{"### Deserialized declaration: ${ir2string(declaration)}"}

        return declaration
    }

    val ByteArray.codedInputStream: org.jetbrains.kotlin.protobuf.CodedInputStream
        get() {
            val codedInputStream = org.jetbrains.kotlin.protobuf.CodedInputStream.newInstance(this)
            codedInputStream.setRecursionLimit(65535) // The default 64 is blatantly not enough for IR.
            return codedInputStream
        }

    fun deserializeIrFile(fileProto: KonanIr.IrFile, reader: (Long)->ByteArray): IrFile {
        val fileEntry = NaiveSourceBasedFileEntryImpl(fileProto.fileEntry.name)

        val dummyPackageFragmentDescriptor = EmptyPackageFragmentDescriptor(currentModule!!, FqName("THE_DESCRIPTOR_SHOULD_NOT_BE_NEEDED"))


        val symbol = IrFileSymbolImpl(dummyPackageFragmentDescriptor)
        val file = IrFileImpl(fileEntry, symbol , FqName(fileProto.fqName))
        fileProto.declarationIdList.forEach {
            val stream = reader(it.index).codedInputStream
            val proto = KonanIr.IrDeclaration.parseFrom(stream, KonanSerializerProtocol.extensionRegistry)
            val declaration = deserializeDeclaration(proto, file)
            file.declarations.add(declaration)
            //declaration.parent = file
        }
        return file
    }

    fun deserializeIrModule(proto: KonanIr.IrModule, reader: (Long)->ByteArray): IrModuleFragment {

        val files = proto.fileList.map {
            deserializeIrFile(it, reader)

        }
        return IrModuleFragmentImpl(currentModule!!, builtIns, files)
    }

    fun deserializedIrModule(moduleDescriptor: ModuleDescriptor, byteArray: ByteArray, reader: (Long)->ByteArray): IrModuleFragment {
        currentModule = moduleDescriptor
        val proto = KonanIr.IrModule.parseFrom(byteArray.codedInputStream, KonanSerializerProtocol.extensionRegistry)
        return deserializeIrModule(proto, reader)
    }
}


package me.coley.recaf.parse.bytecode;

import me.coley.recaf.util.OpcodeUtil;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * A modified version of ASM's {@link BasicVerifier} to use {@link RValue}.<br>
 * Additionally, a few extra verification steps are taken and simple math and types are calculated.
 *
 * @author Matt
 */
public class RInterpreter extends Interpreter<RValue> {
	RInterpreter() {
		super(Opcodes.ASM8);
	}

	@Override
	public RValue newValue(Type type) {
		return RValue.of(type);
	}

	@Override
	public RValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
		if (isPrimitive(type))
			return RValue.of(type);
		return RValue.ofVirtual(type);
	}

	@Override
	public RValue newExceptionValue(TryCatchBlockNode tryCatch,
									Frame<RValue> handlerFrame, Type exceptionType) {
		return RValue.ofVirtual(exceptionType);
	}

	@Override
	public RValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
		switch (insn.getOpcode()) {
			case ACONST_NULL:
				return RValue.NULL;
			case ICONST_M1:
				return RValue.of(-1);
			case ICONST_0:
				return RValue.of(0);
			case ICONST_1:
				return RValue.of(1);
			case ICONST_2:
				return RValue.of(2);
			case ICONST_3:
				return RValue.of(3);
			case ICONST_4:
				return RValue.of(4);
			case ICONST_5:
				return RValue.of(5);
			case LCONST_0:
				return RValue.of(0L);
			case LCONST_1:
				return RValue.of(1L);
			case FCONST_0:
				return RValue.of(0.0F);
			case FCONST_1:
				return RValue.of(1.0F);
			case FCONST_2:
				return RValue.of(2.0F);
			case DCONST_0:
				return RValue.of(0.0);
			case DCONST_1:
				return RValue.of(1.0);
			case BIPUSH:
			case SIPUSH:
				return RValue.of(((IntInsnNode) insn).operand);
			case LDC:
				Object value = ((LdcInsnNode) insn).cst;
				if (value instanceof Integer) {
					return RValue.of((int) value);
				} else if (value instanceof Float) {
					return RValue.of((float) value);
				} else if (value instanceof Long) {
					return RValue.of((long) value);
				} else if (value instanceof Double) {
					return RValue.of((double) value);
				} else if (value instanceof String) {
					return RValue.of((String) value);
				} else if (value instanceof Type) {
					Type type =  (Type) value;
					int sort = type.getSort();
					if (sort == Type.OBJECT || sort == Type.ARRAY) {
						return RValue.ofClass(Type.getObjectType("java/lang/Class"), type);
					} else if (sort == Type.METHOD) {
						return RValue.ofVirtual(Type.getObjectType("java/lang/invoke/MethodType"));
					} else {
						throw new AnalyzerException(insn, "Illegal LDC value " + value);
					}
				} else if (value instanceof Handle) {
					return RValue.ofVirtual(Type.getObjectType("java/lang/invoke/MethodHandle"));
				} else if (value instanceof ConstantDynamic) {
					return RValue.ofVirtual(Type.getType(((ConstantDynamic) value).getDescriptor()));
				} else {
					throw new AnalyzerException(insn, "Illegal LDC value " + value);
				}
			case JSR:
				return RValue.RETURNADDRESS_VALUE;
			case GETSTATIC:
				Type type = Type.getType(((FieldInsnNode) insn).desc);
				if (!isPrimitive(type))
					return RValue.ofVirtual(type);
				return RValue.of(type);
			case NEW:
				return RValue.ofVirtual(Type.getObjectType(((TypeInsnNode) insn).desc));
			default:
				throw new IllegalStateException();
		}
	}

	@Override
	public RValue copyOperation(AbstractInsnNode insn, RValue value) throws AnalyzerException {
		// Fetch type from instruction
		Type insnType = null;
		boolean load = false;
		switch(insn.getOpcode()) {
			case ILOAD:
				load = true;
			case ISTORE:
				insnType = Type.INT_TYPE;
				break;
			case LLOAD:
				load = true;
			case LSTORE:
				insnType = Type.LONG_TYPE;
				break;
			case FLOAD:
				load = true;
			case FSTORE:
				insnType = Type.FLOAT_TYPE;
				break;
			case DLOAD:
				load = true;
			case DSTORE:
				insnType = Type.DOUBLE_TYPE;
				break;
			case ALOAD:
				load = true;
				if (!value.isUninitialized() && !value.isReference())
					throw new AnalyzerException(insn, "Expected a reference type.");
				insnType = value.getType();
				break;
			case ASTORE:
				if (!value.isReference() && !value.isJsrRet())
					throw new AnalyzerException(insn, "Expected a reference or return-address type.");
				insnType = value.getType();
				break;
			default:
				break;
		}
		// Very simple type verification, don't try to mix primitives and non-primitives
		Type argType = value.getType();
		if(insnType != null && argType != null) {
			if(insnType.getSort() == Type.OBJECT && isPrimitive(argType))
				throw new AnalyzerException(insn, "Cannot mix primitive with type-variable instruction " +
						OpcodeUtil.opcodeToName(insn.getOpcode()));
			else if(argType.getSort() == Type.OBJECT && isPrimitive(insnType))
				throw new AnalyzerException(insn, "Cannot mix type with primitive-variable instruction " +
						OpcodeUtil.opcodeToName(insn.getOpcode()));
		}
		// If we're operating on a load-instruction we want the return value to
		// relate to the value of the instruction, not the passed value.
		if(load && insnType != null) {
			if(!isPrimitive(insnType))
				return RValue.ofVirtual(insnType);
			return RValue.of(insnType);
		}
		return value;
	}

	@Override
	public RValue unaryOperation(AbstractInsnNode insn, RValue value) throws AnalyzerException {
		switch(insn.getOpcode()) {
			case INEG:
				if (value.getValue() == null)
					return RValue.of(Type.INT_TYPE);
				return RValue.of(-(int) value.getValue());
			case IINC:
				return RValue.of(((IincInsnNode) insn).incr);
			case L2I:
			case F2I:
			case D2I:
			case I2B:
			case I2C:
			case I2S:
				if (value.getValue() == null)
					return RValue.of(Type.INT_TYPE);
				return RValue.of(((Number) value.getValue()).intValue());
			case FNEG:
				if (value.getValue() == null)
					return RValue.of(Type.FLOAT_TYPE);
				return RValue.of(-(float) value.getValue());
			case I2F:
			case L2F:
			case D2F:
				if (value.getValue() == null)
					return RValue.of(Type.FLOAT_TYPE);
				return RValue.of((float) value.getValue());
			case LNEG:
				if (value.getValue() == null)
					return RValue.of(Type.LONG_TYPE);
				return RValue.of(-(long) value.getValue());
			case I2L:
			case F2L:
			case D2L:
				if (value.getValue() == null)
					return RValue.of(Type.LONG_TYPE);
				return RValue.of(((Number) value.getValue()).longValue());
			case DNEG:
				if (value.getValue() == null)
					return RValue.of(Type.DOUBLE_TYPE);
				return RValue.of(-(double) value.getValue());
			case I2D:
			case L2D:
			case F2D:
				if (value.getValue() == null)
					return RValue.of(Type.DOUBLE_TYPE);
				RValue.of(((Number) value.getValue()).doubleValue());
			case IFEQ:
			case IFNE:
			case IFLT:
			case IFGE:
			case IFGT:
			case IFLE:
			case TABLESWITCH:
			case LOOKUPSWITCH:
				if (!(isSubTypeOf(value.getType(), Type.INT_TYPE) || isSubTypeOf(value.getType(), Type.BOOLEAN_TYPE)))
					throw new AnalyzerException(insn, "Expected int type.");
				return null;
			case IRETURN:
				if (!(isSubTypeOf(value.getType(), Type.INT_TYPE) || isSubTypeOf(value.getType(), Type.BOOLEAN_TYPE)))
					throw new AnalyzerException(insn, "Expected int return type.");
				return null;
			case LRETURN:
				if (!isSubTypeOf(value.getType(), Type.LONG_TYPE))
					throw new AnalyzerException(insn, "Expected long return type.");
				return null;
			case FRETURN:
				if (!isSubTypeOf(value.getType(), Type.FLOAT_TYPE))
					throw new AnalyzerException(insn, "Expected float return type.");
				return null;
			case DRETURN:
				if (!isSubTypeOf(value.getType(), Type.DOUBLE_TYPE))
					throw new AnalyzerException(insn, "Expected double return type.");
				return null;
			case ARETURN:
				if (!value.isReference())
					throw new AnalyzerException(insn, "Expected a reference type.");
				return null;
			case PUTSTATIC: {
				// Value == item on stack
				FieldInsnNode fin = (FieldInsnNode) insn;
				Type fieldType = Type.getType(fin.desc);
				if(!isSubTypeOf(value.getType(), fieldType))
					throw new AnalyzerException(insn, "Expected type: " + fieldType);
				return null;
			}
			case GETFIELD: {
				// Value == field owner instance
				// - Check instance context is of the owner class
				FieldInsnNode fin = (FieldInsnNode) insn;
				if(!isSubTypeOf(value.getType(), Type.getObjectType(fin.owner)))
					throw new AnalyzerException(insn, "Expected type: " + fin.owner);
				Type type = Type.getType(fin.desc);
				if(!isPrimitive(type))
					return RValue.ofVirtual(type);
				return RValue.of(type);
			}
			case NEWARRAY:
				switch(((IntInsnNode) insn).operand) {
					case T_BOOLEAN:
						return RValue.ofVirtual(Type.getType("[Z"));
					case T_CHAR:
						return RValue.ofVirtual(Type.getType("[C"));
					case T_BYTE:
						return RValue.ofVirtual(Type.getType("[B"));
					case T_SHORT:
						return RValue.ofVirtual(Type.getType("[S"));
					case T_INT:
						return RValue.ofVirtual(Type.getType("[I"));
					case T_FLOAT:
						return RValue.ofVirtual(Type.getType("[F"));
					case T_DOUBLE:
						return RValue.ofVirtual(Type.getType("[D"));
					case T_LONG:
						return RValue.ofVirtual(Type.getType("[J"));
					default:
						break;
				}
				throw new AnalyzerException(insn, "Invalid array type");
			case ANEWARRAY:
				return RValue.ofVirtual(Type.getType("[" + Type.getObjectType(((TypeInsnNode) insn).desc)));
			case ARRAYLENGTH:
				if (value.getValue() instanceof RVirtual && !((RVirtual) value.getValue()).isArray())
					throw new AnalyzerException(insn, "Expected an array type.");
				return RValue.of(Type.INT_TYPE);
			case ATHROW:
				if (!value.isReference())
					throw new AnalyzerException(insn, "Missing exception type on stack.");
				return null;
			case CHECKCAST:
				if (!value.isReference())
					throw new AnalyzerException(insn, "Expected a reference type.");
				return RValue.ofVirtual(Type.getObjectType(((TypeInsnNode) insn).desc));
			case INSTANCEOF:
				return RValue.of(Type.INT_TYPE);
			case MONITORENTER:
			case MONITOREXIT:
			case IFNULL:
			case IFNONNULL:
				if (!value.isReference())
					throw new AnalyzerException(insn, "Expected a reference type.");
				return null;
			default:
				throw new IllegalStateException();
		}
	}

	@Override
	public RValue binaryOperation(AbstractInsnNode insn, RValue value1, RValue value2) throws AnalyzerException {
		// Modified from BasicVerifier
		Type expected1;
		Type expected2;
		switch (insn.getOpcode()) {
			case IALOAD:
				expected1 = Type.getType("[I");
				expected2 = Type.INT_TYPE;
				break;
			case BALOAD:
				if (isSubTypeOf(value1.getType(), Type.getType("[Z"))) {
					expected1 = Type.getType("[Z");
				} else {
					expected1 = Type.getType("[B");
				}
				expected2 = Type.INT_TYPE;
				break;
			case CALOAD:
				expected1 = Type.getType("[C");
				expected2 = Type.INT_TYPE;
				break;
			case SALOAD:
				expected1 = Type.getType("[S");
				expected2 = Type.INT_TYPE;
				break;
			case LALOAD:
				expected1 = Type.getType("[J");
				expected2 = Type.INT_TYPE;
				break;
			case FALOAD:
				expected1 = Type.getType("[F");
				expected2 = Type.INT_TYPE;
				break;
			case DALOAD:
				expected1 = Type.getType("[D");
				expected2 = Type.INT_TYPE;
				break;
			case AALOAD:
				expected1 = Type.getType("[Ljava/lang/Object;");
				expected2 = Type.INT_TYPE;
				break;
			case IADD:
			case ISUB:
			case IMUL:
			case IDIV:
			case IREM:
			case ISHL:
			case ISHR:
			case IUSHR:
			case IAND:
			case IOR:
			case IXOR:
			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPGE:
			case IF_ICMPGT:
			case IF_ICMPLE:
				expected1 = Type.INT_TYPE;
				expected2 = Type.INT_TYPE;
				break;
			case FADD:
			case FSUB:
			case FMUL:
			case FDIV:
			case FREM:
			case FCMPL:
			case FCMPG:
				expected1 = Type.FLOAT_TYPE;
				expected2 = Type.FLOAT_TYPE;
				break;
			case LADD:
			case LSUB:
			case LMUL:
			case LDIV:
			case LREM:
			case LAND:
			case LOR:
			case LXOR:
			case LCMP:
				expected1 = Type.LONG_TYPE;
				expected2 = Type.LONG_TYPE;
				break;
			case LSHL:
			case LSHR:
			case LUSHR:
				expected1 = Type.LONG_TYPE;
				expected2 = Type.INT_TYPE;
				break;
			case DADD:
			case DSUB:
			case DMUL:
			case DDIV:
			case DREM:
			case DCMPL:
			case DCMPG:
				expected1 = Type.DOUBLE_TYPE;
				expected2 = Type.DOUBLE_TYPE;
				break;
			case IF_ACMPEQ:
			case IF_ACMPNE:
				expected1 = Type.getObjectType("java/lang/Object");
				expected2 = Type.getObjectType("java/lang/Object");
				break;
			case PUTFIELD:
				FieldInsnNode fieldInsn = (FieldInsnNode) insn;
				expected1 = Type.getObjectType(fieldInsn.owner);
				expected2 = Type.getType(fieldInsn.desc);
				break;
			default:
				throw new IllegalStateException();
		}
		if (!value1.isUninitialized() && !value2.isUninitialized())
			if (!isSubTypeOfOrNull(value1, expected1))
				throw new AnalyzerException(insn, "First argument not of expected type", expected1, value1);
			else if (!isSubTypeOfOrNull(value2, expected2))
				throw new AnalyzerException(insn, "Second argument not of expected type", expected2, value2);
		// Update values
		switch(insn.getOpcode()) {
			case IADD:
			case FADD:
			case LADD:
			case DADD:
				return value1.add(value2);
			case ISUB:
			case FSUB:
			case LSUB:
			case DSUB:
				return value1.sub(value2);
			case IMUL:
			case FMUL:
			case LMUL:
			case DMUL:
				return value1.mul(value2);
			case IDIV:
			case FDIV:
			case LDIV:
			case DDIV:
				return value1.div(value2);
			case IREM:
			case FREM:
			case LREM:
			case DREM:
				return value1.rem(value2);
			case ISHL:
			case LSHL:
				return value1.shl(value2);
			case ISHR:
			case LSHR:
				return value1.shr(value2);
			case IUSHR:
			case LUSHR:
				return value1.ushr(value2);
			case IAND:
			case LAND:
				return value1.and(value2);
			case IOR:
			case LOR:
				return value1.or(value2);
			case IXOR:
			case LXOR:
				return value1.xor(value2);
			case FALOAD:
				return RValue.of(Type.FLOAT_TYPE);
			case LALOAD:
				return RValue.of(Type.LONG_TYPE);
			case DALOAD:
				return RValue.of(Type.DOUBLE_TYPE);
			case AALOAD:
				if (value1.getType() == null)
					return RValue.ofVirtual(Type.getObjectType("java/lang/Object"));
				else
					return RValue.ofVirtual(Type.getType(value1.getType().getDescriptor().substring(1)));
			case IALOAD:
			case BALOAD:
			case CALOAD:
			case SALOAD:
				return RValue.of(Type.INT_TYPE);
			case LCMP:
			case FCMPL:
			case FCMPG:
			case DCMPL:
			case DCMPG:
				if (value1.getValue() == null || value2.getValue() == null)
					return RValue.of(Type.INT_TYPE);
				double v1 = (double) value1.getValue();
				double v2 = (double) value1.getValue();
				if(v1 > v2)
					return RValue.of(1);
				else if(v1 < v2)
					return RValue.of(-1);
				else
					return RValue.of(0);
			case IF_ICMPEQ:
			case IF_ICMPNE:
			case IF_ICMPLT:
			case IF_ICMPGE:
			case IF_ICMPGT:
			case IF_ICMPLE:
			case IF_ACMPEQ:
			case IF_ACMPNE:
			case PUTFIELD:
				return null;
			default:
				throw new IllegalStateException();
		}
	}

	@Override
	public RValue ternaryOperation(AbstractInsnNode insn, RValue value1, RValue value2,
								   RValue value3) throws AnalyzerException {
		Type expected1;
		Type expected3;
		switch(insn.getOpcode()) {
			case IASTORE:
				expected1 = Type.getType("[I");
				expected3 = Type.INT_TYPE;
				break;
			case BASTORE:
				if(isSubTypeOf(value1.getType(), Type.getType("[Z"))) {
					expected1 = Type.getType("[Z");
				} else {
					expected1 = Type.getType("[B");
				}
				expected3 = Type.INT_TYPE;
				break;
			case CASTORE:
				expected1 = Type.getType("[C");
				expected3 = Type.INT_TYPE;
				break;
			case SASTORE:
				expected1 = Type.getType("[S");
				expected3 = Type.INT_TYPE;
				break;
			case LASTORE:
				expected1 = Type.getType("[J");
				expected3 = Type.LONG_TYPE;
				break;
			case FASTORE:
				expected1 = Type.getType("[F");
				expected3 = Type.FLOAT_TYPE;
				break;
			case DASTORE:
				expected1 = Type.getType("[D");
				expected3 = Type.DOUBLE_TYPE;
				break;
			case AASTORE:
				expected1 = value1.getType();
				expected3 = Type.getObjectType("java/lang/Object");
				break;
			default:
				throw new AssertionError();
		}
		if(!isSubTypeOf(value1.getType(), expected1))
			throw new AnalyzerException(insn, "First argument not of expected type", expected1, value1);
		else if(!Type.INT_TYPE.equals(value2.getType()))
			throw new AnalyzerException(insn, "Second argument not an integer", BasicValue.INT_VALUE, value2);
		else if(!isSubTypeOf(value3.getType(), expected3))
			throw new AnalyzerException(insn, "Second argument not of expected type", expected3, value3);
		return null;
	}

	@Override
	public RValue naryOperation(AbstractInsnNode insn, List<? extends RValue> values) throws AnalyzerException {
		int opcode = insn.getOpcode();
		if (opcode == MULTIANEWARRAY) {
			// Multi-dimensional array args must all be numeric
			for (RValue value : values)
				if (!Type.INT_TYPE.equals(value.getType()))
					throw new AnalyzerException(insn, "MULTIANEWARRAY argument was not numeric!", RValue.of(Type.INT_TYPE), value);
			return RValue.ofVirtual(Type.getType(((MultiANewArrayInsnNode) insn).desc));
		} else {
			// From BasicVerifier
			int i = 0;
			int j = 0;
			if(opcode != INVOKESTATIC && opcode != INVOKEDYNAMIC) {
				Type owner = Type.getObjectType(((MethodInsnNode) insn).owner);
				if(!isSubTypeOf(values.get(i++).getType(), owner))
					throw new AnalyzerException(insn, "Method owner does not match type on stack",
							newValue(owner), values.get(0));
			}
			String methodDescriptor = (opcode == INVOKEDYNAMIC) ?
					((InvokeDynamicInsnNode) insn).desc :
					((MethodInsnNode) insn).desc;
			Type[] args = Type.getArgumentTypes(methodDescriptor);
			while(i < values.size()) {
				Type expected = args[j++];
				RValue actual = values.get(i++);
				if(!isSubTypeOfOrNull(actual, expected)) {
					throw new AnalyzerException(insn, "Argument type was \"" + actual +
							"\" but expected \"" + expected + "\"");
				}
			}
			// Get value
			if (opcode == INVOKEDYNAMIC) {
				Type retType = Type.getReturnType(((InvokeDynamicInsnNode) insn).desc);
				if (!isPrimitive(retType))
					return RValue.ofVirtual(retType);
				return RValue.of(retType);
			} else if (opcode == INVOKESTATIC) {
				Type retType = Type.getReturnType(((MethodInsnNode) insn).desc);
				if (!isPrimitive(retType))
					return RValue.ofVirtual(retType);
				return RValue.of(retType);
			} else {
				// INVOKEVIRTUAL, INVOKESPECIAL, INVOKEINTERFACE
				RValue ownerValue = values.get(0);
				if(ownerValue.isUninitialized())
					throw new AnalyzerException(insn, "Cannot call method on uninitialized reference");
				else if(ownerValue.isNullConst())
					throw new AnalyzerException(insn, "Cannot call method on null reference");
				return ownerValue.ref(Type.getMethodType(((MethodInsnNode)insn).desc));
			}
		}
	}

	@Override
	public void returnOperation(AbstractInsnNode insn, RValue value, RValue expected) throws AnalyzerException {
		if(!isSubTypeOfOrNull(value, expected))
			throw new AnalyzerException(insn, "Incompatible return type", expected, value);
	}

	@Override
	public RValue merge(RValue value1, RValue value2) {
		// Handle null
		//  - NULL can be ANY type, so... it wins the "common super type" here
		if(value2.isNullConst())
			return value1.isNullConst() ? RValue.NULL : value1;
		if(value1.isNullConst())
			return value2.isNullConst() ? RValue.NULL :  RValue.of(value2.getType());
		// Check standard merge
		if(value1.canMerge(value2))
			return value1;
		else if(value2.canMerge(value1))
			return value2;
		return RValue.UNINITIALIZED;
	}

	private static boolean isSubTypeOfOrNull(RValue value, RValue expected) {
		return isSubTypeOfOrNull(value, expected.getType());
	}

	private static boolean isSubTypeOfOrNull(RValue value, Type expected) {
		// Null type and primitives do not mix.
		// Null types and object types do.
		if (value.isNullConst() && !isPrimitive(expected))
			return true;
		// Uninitialized values are not subtypes
		if (value.isUninitialized())
			return false;
		// Fallback
		return isSubTypeOf(value.getType(), expected);
	}

	private static boolean isSubTypeOf(Type child, Type parent) {
		// Can't handle null type
		if (child == null)
			return false;
		// Simple equality check
		if (child.equals(parent))
			return true;
		// Look at array element type
		boolean bothArrays = child.getSort() == Type.ARRAY && parent.getSort() == Type.ARRAY;
		if (bothArrays) {
			// Dimensions must match
			if (child.getDimensions() != parent.getDimensions())
				return false;
			// TODO: With usage cases of "isSubTypeOf(...)" should we just check the element types are equals?
			//  - Or should sub-typing with array element types be used like it currently is?
			child = child.getElementType();
			parent = parent.getElementType();
		}
		// Null check in case
		if(parent == null)
			return false;
		// Treat lesser primitives as integers.
		//  - Because of boolean consts are ICONST_0/ICONST_1
		//  - Short parameters take the stack value of BIPUSH (int)
		if(parent.getSort() >= Type.BOOLEAN && parent.getSort() <= Type.INT)
			parent = Type.INT_TYPE;
		// Check for primitives
		//  - ASM sorts are in a specific order
		//  - If the expected sort is a larger type (greater sort) then the given type can
		//    be assumed to be compatible.
		if (isPrimitive(parent) && isPrimitive(child))
			return parent.getSort() >= child.getSort();
		// Use a simplified check if the expected type is just "Object"
		//  - Most things can be lumped into an object
		if (!isPrimitive(child) && parent.getDescriptor().equals("Ljava/lang/Object;"))
			return true;
		// Check if types are compatible
		if (child.getSort() == parent.getSort()) {
			RValue host = RValue.of(parent);
			return host != null && host.canMerge(RValue.of(child));
		}
		return false;
	}

	private static boolean isPrimitive(Type type) {
		return type.getSort() < Type.ARRAY;
	}
}

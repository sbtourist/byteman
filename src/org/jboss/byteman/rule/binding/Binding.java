/*
* JBoss, Home of Professional Open Source
* Copyright 2008, Red Hat Middleware LLC, and individual contributors
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*
* @authors Andrew Dinn
*/
package org.jboss.byteman.rule.binding;

import org.jboss.byteman.rule.type.Type;
import org.jboss.byteman.rule.expression.Expression;
import org.jboss.byteman.rule.exception.TypeException;
import org.jboss.byteman.rule.exception.ExecuteException;
import org.jboss.byteman.rule.exception.CompileException;
import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.RuleElement;
import org.jboss.byteman.rule.compiler.StackHeights;
import org.jboss.byteman.rule.helper.HelperAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.StringWriter;

/**
 * Class used to store a binding of a named variable to a value of some given type
 */

public class Binding extends RuleElement
{

    public Binding(Rule rule, String name)
    {
        this(rule, name, Type.UNDEFINED, null);
    }

    public Binding(Rule rule, String name, Type type)
    {
        this(rule, name, type, null);
    }

    public Binding(Rule rule, String name, Type type, Expression value)
    {
        super(rule);
        this.name = name;
        this.type = (type != null ? type : Type.UNDEFINED);
        this.value = value;
        // ok, check the name to see what type of binding we have
        if (name.matches("\\$[0-9].*")) {
            // $NNN references the method target or a parameter from 0 upwards
            index = Integer.valueOf(name.substring(1));
        } else if (name.equals("$$")) {
            // $$ references the helper implicitly associated with a builtin call
            index = HELPER;
        } else if (name.equals("$!")) {
            // $! refers to the current return value for the triggger method and is only valid when
            // the rule is triggered AT EXIT
            index = RETURN;
        } else if (name.matches("\\$[A-Za-z].*")) {
           // $AAAAA refers  to a local variable in the trigger method
            index = LOCALVAR;
        } else {
            // anything else must be a variable introduced in the BINDS clause
            index = BINDVAR;
        }
        this.objectArrayIndex = 0;
    }

    public Type typeCheck(Type expected)
            throws TypeException
    {
        // value can be null if this is a rule method parameter
        if (value != null) {
            // type check the binding expression, using the bound variable's expected if it is known

            Type valueType = value.typeCheck(expected);

            if (type.isUndefined()) {
                type = valueType;
            }
        } else if (type.isUndefined()) {
            // can we have no expected for a method parameter?
            throw new TypeException("Binding.typecheck unknown type for binding " + name);
        }
        return type;
    }

    public Object interpret(HelperAdapter helper) throws ExecuteException
    {
        if (isBindVar()) {
            Object result = value.interpret(helper);
            helper.bindVariable(getName(), result);
            return result;
        }
        return null;
    }

    public void compile(MethodVisitor mv, StackHeights currentStackHeights, StackHeights maxStackHeights) throws CompileException
    {
        if (isBindVar()) {
            int currentStack = currentStackHeights.stackCount;

            // push the current helper instance i.e. this -- adds 1 to stack height
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // push the variable name -- adds 1 to stack height
            mv.visitLdcInsn(name);
            // increment stack count
            currentStackHeights.addStackCount(2);
            // compile the rhs expression for the binding -- adds 1 to stack height
            value.compile(mv, currentStackHeights, maxStackHeights);
            // make sure value is boxed if necessary
            if (type.isPrimitive()) {
                compileBox(Type.boxType(type), mv, currentStackHeights, maxStackHeights);
            }
            // compile a bindVariable call pops 3 from stack height
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.internalName(HelperAdapter.class), "bindVariable", "(Ljava/lang/String;Ljava/lang/Object;)V");
            currentStackHeights.addStackCount(-3);

            // check the max height was enough for 3 extra values

            // we needed room for 3 more values on the stack -- make sure we got it
            int maxStack = maxStackHeights.stackCount;
            int overflow = (currentStack + 3) - maxStack;

            if (overflow > 0) {
                maxStackHeights.addStackCount(overflow);
            }
        }
    }

    public String getName()
    {
        return name;
    }

    public Expression getValue()
    {
        return value;
    }

    public Expression setValue(Expression value)
    {
        Expression oldValue = this.value;
        this.value = value;

        return oldValue;
    }

    public Type getType()
    {
        return type;
    }

    public void setType(Type type)
    {
        this.type = type;
    }

    public int getObjectArrayIndex()
    {
        return objectArrayIndex;
    }

    public void setObjectArrayIndex(int objectArrayIndex)
    {
        this.objectArrayIndex = objectArrayIndex;
    }

    public int getLocalIndex()
    {
        return localIndex;
    }

    public void setLocalIndex(int localIndex)
    {
        this.localIndex = localIndex;
    }

    public boolean isParam()
    {
        return index > 0;
    }

    public boolean isRecipient()
    {
        return index == 0;
    }

    public boolean isHelper()
    {
        return index == HELPER;
    }

    public boolean isReturn()
    {
        return index == RETURN;
    }

    public boolean isLocalVar()
    {
        return index == LOCALVAR;
    }

    public boolean isBindVar()
    {
        return index <= BINDVAR;
    }

    public int getIndex()
    {
        return index;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(String desc) {
        this.descriptor = desc;
    }

    public void writeTo(StringWriter stringWriter)
    {
        if (isHelper()) {
            stringWriter.write(name);
        } else if (isParam() || isRecipient()) {
            stringWriter.write(name);
            if (type != null && (type.isDefined() || type.isObject())) {
                stringWriter.write(" : ");
                stringWriter.write(type.getName());
            }
        } else {
            stringWriter.write(name);
            if (type != null && (type.isDefined() || type.isObject())) {
                stringWriter.write(" : ");
                stringWriter.write(type.getName());
            }
        }
        if (value != null) {
            stringWriter.write(" = ");
            value.writeTo(stringWriter);
        }
    }

    // special index values for non-positional parameters

    private final static int HELPER = -1;
    private final static int RETURN = -2;
    private final static int LOCALVAR = -3;
    private final static int BINDVAR = -4;

    private String name;
    private String descriptor; // supplied when the binding is for a local var
    private Type type;
    private Expression value;
    // the position index of the trigger method recipient or a trigger method parameter or one of the special index
    // values for other types  of parameters.
    private int index;
    // the offset into the trigger method Object array of the initial value for this parameter
    private int objectArrayIndex;
    // the offset into the stack at which a local var is located
    private int localIndex;
}
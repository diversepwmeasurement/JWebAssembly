/*
   Copyright 2020 Volker Berlin (i-net software)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/
package de.inetsoftware.jwebassembly.module;

import java.io.IOException;

import javax.annotation.Nonnull;

import de.inetsoftware.jwebassembly.WasmException;
import de.inetsoftware.jwebassembly.module.TypeManager.StructType;
import de.inetsoftware.jwebassembly.wasm.MemoryOperator;
import de.inetsoftware.jwebassembly.wasm.NamedStorageType;
import de.inetsoftware.jwebassembly.wasm.StructOperator;
import de.inetsoftware.jwebassembly.wasm.ValueType;
import de.inetsoftware.jwebassembly.wasm.VariableOperator;
import de.inetsoftware.jwebassembly.wasm.WasmOptions;

/**
 * WasmInstruction for a function call.
 * 
 * @author Volker Berlin
 *
 */
class WasmCallInterfaceInstruction extends WasmCallIndirectInstruction {

    private final WasmOptions options;

    /**
     * Create an instance of a function call instruction
     * 
     * @param name
     *            the function name that should be called
     * @param javaCodePos
     *            the code position/offset in the Java method
     * @param lineNumber
     *            the line number in the Java source code
     * @param types
     *            the type manager
     * @param options
     *            compiler properties
     */
    WasmCallInterfaceInstruction( FunctionName name, int javaCodePos, int lineNumber, TypeManager types, WasmOptions options ) {
        super( name, javaCodePos, lineNumber, types );
        this.options = options;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Type getType() {
        return Type.CallInterface;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean isVirtual() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    public void writeTo( @Nonnull ModuleWriter writer ) throws IOException {
        FunctionName name = getFunctionName();
        StructType type = getThisType();
        int classIndex = type.getClassIndex();

        //writer.writeLocal( VariableOperator.get, tempVarIdx ); // duplicate this on the stack
        writer.writeConst( classIndex, ValueType.i32 );
        //writer.writeConst( functionIndex, ValueType.i32 );
        //writer.writeFunctionCall( callInterface ); // parameters: this, classIndex, functionIndex

        //writer.writeVirtualFunctionCall( name, type );
        throw new WasmException( "Interface calls are not supported.", getLineNumber() );
    }
}

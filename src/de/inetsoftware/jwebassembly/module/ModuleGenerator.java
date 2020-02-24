/*
 * Copyright 2017 - 2020 Volker Berlin (i-net software)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.inetsoftware.jwebassembly.module;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import de.inetsoftware.classparser.ClassFile;
import de.inetsoftware.classparser.Code;
import de.inetsoftware.classparser.ConstantClass;
import de.inetsoftware.classparser.MethodInfo;
import de.inetsoftware.jwebassembly.JWebAssembly;
import de.inetsoftware.jwebassembly.WasmException;
import de.inetsoftware.jwebassembly.javascript.JavaScriptWriter;
import de.inetsoftware.jwebassembly.module.TypeManager.StructType;
import de.inetsoftware.jwebassembly.wasm.AnyType;
import de.inetsoftware.jwebassembly.wasm.NamedStorageType;
import de.inetsoftware.jwebassembly.wasm.StructOperator;
import de.inetsoftware.jwebassembly.wasm.ValueType;
import de.inetsoftware.jwebassembly.wasm.WasmOptions;
import de.inetsoftware.jwebassembly.watparser.WatParser;

/**
 * Generate the WebAssembly output.
 * 
 * @author Volker Berlin
 */
public class ModuleGenerator {

    private final ModuleWriter              writer;

    private final JavaScriptWriter          javaScript;

    private final ClassFileLoader           classFileLoader;

    private final JavaMethodWasmCodeBuilder javaCodeBuilder;

    private final WatParser                 watParser;

    private String                          sourceFile;

    private String                          className;

    private FunctionManager                 functions = new FunctionManager();

    private TypeManager                     types = new TypeManager();

    private StringManager                   strings = new StringManager();

    private CodeOptimizer                   optimizer = new CodeOptimizer();

    /**
     * Create a new generator.
     * 
     * @param writer
     *            the target writer
     * @param target
     *            the target for the module data
     * @param libraries
     *            libraries 
     */
    public ModuleGenerator( @Nonnull ModuleWriter writer, WasmTarget target, @Nonnull List<URL> libraries ) {
        this.javaCodeBuilder = new JavaMethodWasmCodeBuilder();
        this.watParser = new WatParser();
        this.writer = writer;
        this.javaScript = new JavaScriptWriter( target );
        this.classFileLoader = new ClassFileLoader( new URLClassLoader( libraries.toArray( new URL[libraries.size()] ) ) );
        WasmOptions options = writer.options;
        types.init( options );
        strings.init( functions );
        javaCodeBuilder.init( types, functions, strings, options, classFileLoader );
        ((WasmCodeBuilder)watParser).init( types, functions, strings, options, classFileLoader );
        scanLibraries( libraries );
    }

    /**
     * Scan the libraries for annotated methods
     * 
     * @param libraries
     *            libraries
     */
    private void scanLibraries( @Nonnull List<URL> libraries ) {
        // search for replacement methods in the libraries
        for( URL url : libraries ) {
            try {
                File file = new File(url.toURI());
                if( file.isDirectory() ) {
                    for( Iterator<Path> iterator = Files.walk( file.toPath() ).iterator(); iterator.hasNext(); ) {
                        Path path = iterator.next();
                        if( path.toString().endsWith( ".class" ) ) {
                            ClassFile classFile = new ClassFile( new BufferedInputStream( Files.newInputStream( path ) ) );
                            prepare( classFile );
                        }
                    }
                }
            } catch( Exception e ) {
                e.printStackTrace();
            }

            try (ZipInputStream input = new ZipInputStream( url.openStream() )) {
                do {
                    ZipEntry entry = input.getNextEntry();
                    if( entry == null ) {
                        break;
                    }
                    if( entry.getName().endsWith( ".class" ) ) {
                        try {
                            ClassFile classFile = new ClassFile( new BufferedInputStream( input ) {
                                @Override
                                public void close() {
                                } // does not close the zip stream
                            } );
                            prepare( classFile );
                        } catch( Throwable th ) {
                            JWebAssembly.LOGGER.log( Level.SEVERE, "Parsing error with " + entry.getName() + " in " + url, th );
                        }
                    }
                } while( true );
            } catch( IOException e ) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Prepare the content of the class.
     * 
     * @param classFile
     *            the class file
     * @throws WasmException
     *             if some Java code can't converted
     * @throws IOException
     *             if any I/O error occur
     */
    public void prepare( ClassFile classFile ) throws IOException {
        classFileLoader.cache( classFile );
        // check if this class replace another class
        Map<String,Object> annotationValues;
        if( (annotationValues = classFile.getAnnotation( JWebAssembly.REPLACE_ANNOTATION )) != null ) {
            String signatureName = (String)annotationValues.get( "value" );
            if( signatureName != null ) {
                classFileLoader.replace( signatureName, classFile );
            }
        }

        // check if this class extends another class with partial code
        if( (annotationValues = classFile.getAnnotation( JWebAssembly.PARTIAL_ANNOTATION )) != null ) {
            String signatureName = (String)annotationValues.get( "value" );
            if( signatureName != null ) {
                classFileLoader.partial( signatureName, classFile );
            }
        }

        iterateMethods( classFile, m -> prepareMethod( m ) );
    }

    /**
     * Scan all needed methods/functions in a loop. If the scan find more needed content then the loop continue.
     * 
     * @throws IOException
     *             if any I/O error occur
     */
    private void scanFunctions() throws IOException {
        FunctionName next;
        NEXT:
        while( (next = functions.nextScannLater()) != null ) {
            className = next.className;
            if( next instanceof SyntheticFunctionName ) {
                JWebAssembly.LOGGER.fine( '\t' + next.methodName + next.signature );
                SyntheticFunctionName synth = (SyntheticFunctionName)next;
                if( synth.hasWasmCode() ) {
                    scanMethod( synth.getCodeBuilder( watParser ) );
                } else {
                    functions.markAsImport( synth, synth.getAnnotation() );
                }
                functions.markAsScanned( next, false );
                continue;
            }

            JWebAssembly.LOGGER.fine( "scan " + next.signatureName );
            MethodInfo method = null;
            ClassFile classFile = classFileLoader.get( next.className );
            if( classFile != null ) {
                sourceFile = classFile.getSourceFile();
                className = classFile.getThisClass().getName();
                method = classFile.getMethod( next.methodName, next.signature );
            }
            if( method == null ) {
                method = functions.replace( next, null );
            }
            if( method != null ) {
                scanMethod( createInstructions( functions.replace( next, method ) ) );
                boolean needThisParameter = !method.isStatic() || "<init>".equals( method.getName() );
                functions.markAsScanned( next, needThisParameter );
                continue;
            }

            // search if there is a super class with the same signature
            ClassFile superClassFile = classFile;
            while( superClassFile != null ) {
                method = superClassFile.getMethod( next.methodName, next.signature );
                if( method != null ) {
                    FunctionName name = new FunctionName( method );
                    functions.markAsNeeded( name );
                    functions.setAlias( next, name );
                    continue NEXT; // we have found a super method
                }
                ConstantClass superClass = superClassFile.getSuperClass();
                superClassFile = superClass == null ? null : classFileLoader.get( superClass.getName() );
            }

            // search if there is a default implementation in an interface
            superClassFile = classFile;
            while( superClassFile != null ) {
                for( ConstantClass iface : superClassFile.getInterfaces() ) {
                    ClassFile iClassFile = classFileLoader.get( iface.getName() );
                    method = iClassFile.getMethod( next.methodName, next.signature );
                    if( method != null ) {
                        FunctionName name = new FunctionName( method );
                        functions.markAsNeeded( name );
                        functions.setAlias( next, name );
                        continue NEXT; // we have found a super method
                    }
                }
                ConstantClass superClass = superClassFile.getSuperClass();
                superClassFile = superClass == null ? null : classFileLoader.get( superClass.getName() );
            }

            throw new WasmException( "Missing function: " + next.signatureName, -1 );
        }
    }

    /**
     * Finish the prepare after all classes/methods are prepare. This must be call before we can start with write the
     * first method.
     * @throws IOException
     *             if any I/O error occur
     */
    public void prepareFinish() throws IOException {
        scanFunctions();

        // write only the needed imports to the output
        for( Iterator<FunctionName> iterator = functions.getNeededImports(); iterator.hasNext(); ) {
            FunctionName name = iterator.next();

            functions.markAsWritten( name );
            Function<String, Object> importAnannotation = functions.getImportAnannotation( name );
            String importModule = (String)importAnannotation.apply( "module" );
            if( importModule == null || importModule.isEmpty() ) {
                // use className if module is not set 
                importModule = name.className.substring( name.className.lastIndexOf( '/' ) + 1 );
            }
            String importName = (String)importAnannotation.apply( "name" );
            if( importName == null || importName.isEmpty() ) {
                // use method name as function if not set
                importName = name.methodName;
            }
            writer.prepareImport( name, importModule, importName );
            writeMethodSignature( name, null );
            javaScript.addImport( importModule, importName, importAnannotation );
        }

        // init/write the function types
        for( Iterator<FunctionName> iterator = functions.getNeededFunctions(); iterator.hasNext(); ) {
            FunctionName name = iterator.next();
            writeMethodSignature( name, null );
        }

        JWebAssembly.LOGGER.fine( "scan finsih" );
        types.prepareFinish( writer, functions, classFileLoader );
        scanFunctions(); // prepare of types can add some override methods as needed
        functions.prepareFinish();
        strings.prepareFinish( writer );
        writer.prepareFinish();
    }

    /**
     * Scan the method and list all needed methods.
     * 
     * @param codeBuilder
     *            the codeBuilder with instructions of the method
     * @throws IOException
     *             if any I/O error occur
     */
    private void scanMethod( WasmCodeBuilder codeBuilder ) throws IOException {
        if( codeBuilder == null ) {
            return;
        }
        List<WasmInstruction> instructions = codeBuilder.getInstructions();
        for( WasmInstruction instruction : instructions ) {
            switch( instruction.getType() ) {
                case Call:
                case CallVirtual:
                    ((WasmCallInstruction)instruction).markAsNeeded( functions );
                    break;
                default:
            }
        }
    }

    /**
     * Finish the code generation.
     * 
     * @throws IOException
     *             if any I/O error occur
     */
    public void finish() throws IOException {
        for( Iterator<FunctionName> it = functions.getWriteLater(); it.hasNext(); ) {
            FunctionName next = it.next();
            sourceFile = null; // clear previous value for the case an IO exception occur
            className = next.className;
            if( next instanceof SyntheticFunctionName ) {
                writeMethodImpl( next, ((SyntheticFunctionName)next).getCodeBuilder( watParser ) );
            } else {
                ClassFile classFile = classFileLoader.get( next.className );
                if( classFile == null ) {
                    throw new WasmException( "Missing function: " + next.signatureName, -1 );
                } else {
                    sourceFile = classFile.getSourceFile();
                    className = classFile.getThisClass().getName();
                    MethodInfo method = classFile.getMethod( next.methodName, next.signature );
                    if( method != null ) {
                        try {
                            Map<String, Object> wat = method.getAnnotation( JWebAssembly.TEXTCODE_ANNOTATION  );
                            if( wat != null ) {
                                String signature = (String)wat.get( "signature" );
                                if( signature == null ) {
                                    signature = method.getType();
                                }
                                next = new FunctionName( method, signature );
                            } else {
                                method = functions.replace( next, method );
                            }
                            if( functions.needToWrite( next ) ) {
                                writeMethod( next, method );
                            }
                        } catch (Throwable ex){
                            throw WasmException.create( ex, sourceFile, className, -1 );
                        }
                    } else {
                        if( functions.needToWrite( next ) ) {
                            throw new WasmException( "Missing function: " + next.signatureName, -1 );
                        }
                    }
                }
            }
        }
        javaScript.finish();
    }

    /**
     * Iterate over all methods of the classFile and run the handler.
     * 
     * @param classFile
     *            the classFile
     * @param handler
     *            the handler
     * @throws WasmException
     *             if some Java code can't converted
     */
    private void iterateMethods( ClassFile classFile, Consumer<MethodInfo> handler ) throws WasmException {
        sourceFile = null; // clear previous value for the case an IO exception occur
        className = null;
        try {
            sourceFile = classFile.getSourceFile();
            className = classFile.getThisClass().getName();
            MethodInfo[] methods = classFile.getMethods();
            for( MethodInfo method : methods ) {
                handler.accept( method );
            }
        } catch( IOException ioex ) {
            throw WasmException.create( ioex, sourceFile, className, -1 );
        }
    }

    /**
     * Prepare the method.
     * 
     * @param method
     *            the method
     * @throws WasmException
     *             if some Java code can't converted
     */
    private void prepareMethod( MethodInfo method ) throws WasmException {
        try {
            FunctionName name = new FunctionName( method );
            if( functions.isKnown( name ) ) {
                return;
            }
            Map<String,Object> annotationValues;
            if( (annotationValues = method.getAnnotation( JWebAssembly.REPLACE_ANNOTATION )) != null ) {
                functions.needThisParameter( name); // register this class that process the annotation of this replacement function not a second time. iSKnown() returns true now.
                String signatureName = (String)annotationValues.get( "value" );
                name = new FunctionName( signatureName );
                functions.addReplacement( name, method );
            }
            if( (annotationValues = method.getAnnotation( JWebAssembly.IMPORT_ANNOTATION )) != null ) {
                if( !method.isStatic() ) {
                    throw new WasmException( "Import method must be static: " + name.fullName, -1 );
                }
                functions.markAsImport( name, annotationValues );
                return;
            }
            if( (annotationValues = method.getAnnotation( JWebAssembly.EXPORT_ANNOTATION )) != null ) {
                if( !method.isStatic() ) {
                    throw new WasmException( "Export method must be static: " + name.fullName, -1 );
                }
                functions.markAsNeeded( name );
                return;
            }
        } catch( Exception ioex ) {
            throw WasmException.create( ioex, sourceFile, className, -1 );
        }
    }

    /**
     * Write the content of a method.
     * 
     * @param name
     *            the function name that should be written. This can be differ from the value in the MethodInfo
     * @param method
     *            the method
     * @throws WasmException
     *             if some Java code can't converted
     * @throws IOException
     *             if any I/O error occur
     */
    private void writeMethod( FunctionName name, MethodInfo method ) throws WasmException, IOException {
        WasmCodeBuilder codeBuilder = createInstructions( method );
        if( codeBuilder == null ) {
            return;
        }
        writeExport( name, method );
        writeMethodImpl( name, codeBuilder );
    }

    /**
     * Create the instructions in a code builder
     * 
     * @param method
     *            the method to parse
     * @return the CodeBuilder or null if it is an import function
     * @throws IOException
     *             if any I/O error occur
     */
    @Nullable
    private WasmCodeBuilder createInstructions( MethodInfo method ) throws IOException {
        Code code = null;
        try {
            Map<String,Object> annotationValues;
            if( (annotationValues = method.getAnnotation( JWebAssembly.IMPORT_ANNOTATION )) != null ) {
                functions.markAsImport( new FunctionName( method ), annotationValues );
                return null;
            }
            code = method.getCode();
            if( method.getAnnotation( JWebAssembly.TEXTCODE_ANNOTATION ) != null ) {
                Map<String, Object> wat = method.getAnnotation( JWebAssembly.TEXTCODE_ANNOTATION );
                String watCode = (String)wat.get( "value" );
                String signature = (String)wat.get( "signature" );
                if( signature == null ) {
                    signature = method.getType();
                }
                watParser.parse( watCode, method, code == null ? -1 : code.getFirstLineNr() );
                return watParser;
            } else if( code != null ) { // abstract methods and interface methods does not have code
                javaCodeBuilder.buildCode( code, method );
                return javaCodeBuilder;
            } else {
                throw new WasmException( "Abstract or native method can not be used: " + new FunctionName( method ).signatureName, -1 );
            }
        } catch( Exception ioex ) {
            int lineNumber = code == null ? -1 : code.getFirstLineNr();
            throw WasmException.create( ioex, sourceFile, className, lineNumber );
        }
    }

    /**
     * Write the method instruction to the Wasm writer.
     * 
     * @param name
     *            the name of the function
     * @param codeBuilder
     *            the code builder with instructions
     * @throws WasmException
     *             if some Java code can't converted
     * @throws IOException
     *             if an i/O error occur
     */
    private void writeMethodImpl( FunctionName name, WasmCodeBuilder codeBuilder ) throws WasmException, IOException {
        writer.writeMethodStart( name, sourceFile );
        functions.markAsWritten( name );
        writeMethodSignature( name, codeBuilder );

        List<WasmInstruction> instructions = codeBuilder.getInstructions();
        optimizer.optimze( instructions );

        int lastJavaSourceLine = -1;
        for( WasmInstruction instruction : instructions ) {
            try {
                // add source-map information
                int javaSourceLine = instruction.getLineNumber();
                if( javaSourceLine >= 0 && javaSourceLine != lastJavaSourceLine ) {
                    writer.markSourceLine( javaSourceLine );
                    lastJavaSourceLine = javaSourceLine;
                }

                switch( instruction.getType() ) {
                    case Block:
                        switch( ((WasmBlockInstruction)instruction).getOperation() ) {
                            case TRY:
                            case CATCH:
                            case THROW:
                            case RETHROW:
                                if( writer.options.useEH() ) {
                                    writer.writeException();
                                }
                                break;
                            default:
                        }
                        break;
                    case Call:
                    case CallVirtual:
                        ((WasmCallInstruction)instruction).markAsNeeded( functions );
                        break;
                    case Struct:
                        if( !writer.options.useGC() ) {
                            break;
                        }
                        WasmStructInstruction instr = (WasmStructInstruction)instruction;
                        if( instr.getOperator() == StructOperator.NEW_DEFAULT ) {
                            StructType structType = instr.getStructType();
                            List<NamedStorageType> list = structType.getFields();
                            for( NamedStorageType storageType : list ) {
                                if( TypeManager.VTABLE == storageType.getName() ) {
                                    writer.writeConst( structType.getVTable(), ValueType.i32 );
                                } else {
                                    writer.writeDefaultValue( storageType.getType() );
                                }
                            }
                        }
                        break;
                    default:
                }

                instruction.writeTo( writer );
            } catch( Throwable th ) {
                throw WasmException.create( th, instruction.getLineNumber() );
            }
        }
        writer.writeMethodFinish();
    }

    /**
     * Look for a Export annotation and if there write an export directive.
     * 
     * @param name
     *            the function name
     * @param method
     *            the method
     * 
     * @throws IOException
     *             if any IOException occur
     */
    private void writeExport( FunctionName name, MethodInfo method ) throws IOException {
        Map<String,Object> export = method.getAnnotation( JWebAssembly.EXPORT_ANNOTATION );
        if( export != null ) {
            String exportName = (String)export.get( "name" );
            if( exportName == null ) {
                exportName = method.getName();  // TODO naming conversion rule if no name was set
            }
            writer.writeExport( name, exportName );
        }
    }

    /**
     * Write the parameter and return signatures
     * 
     * @param name
     *            the Java signature, typical method.getType();
     * @param codeBuilder
     *            the calculated variables 
     * @throws IOException
     *             if any I/O error occur
     * @throws WasmException
     *             if some Java code can't converted
     */
    private void writeMethodSignature( FunctionName name, WasmCodeBuilder codeBuilder ) throws IOException, WasmException {
        writer.writeMethodParamStart( name );
        int paramCount = 0;
        if( functions.needThisParameter( name ) ) {
            StructType instanceType = types.valueOf( name.className );
            writer.writeMethodParam( "param", instanceType, "this" );
            paramCount++;
        }
        Iterator<AnyType> parser = name.getSignature( types );
        AnyType type;
        for( String kind : new String[] {"param","result"}) {
            while( parser.hasNext() && (type = parser.next()) != null ) {
                String paramName = null;
                if( kind == "param" ) {
                    if( codeBuilder != null ) {
                        paramName = codeBuilder.getLocalName( paramCount );
                    }
                    paramCount++;
                }
                if( type != ValueType.empty ) {
                    writer.writeMethodParam( kind, type, paramName );
                }
            }
        }
        if( codeBuilder != null ) {
            List<AnyType> localTypes = codeBuilder.getLocalTypes( paramCount );
            for( int i = 0; i < localTypes.size(); i++ ) {
                type = localTypes.get( i );
                int idx = paramCount + i;
                String paramName = codeBuilder.getLocalName( idx );
                writer.writeMethodParam( "local", type, paramName );
            }
        }
        writer.writeMethodParamFinish( name );
    }

}

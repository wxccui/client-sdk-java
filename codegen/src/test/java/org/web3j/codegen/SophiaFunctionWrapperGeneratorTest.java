package org.web3j.codegen;

import static org.junit.Assert.assertTrue;
import static org.web3j.codegen.FunctionWrapperGenerator.JAVA_TYPES_ARG;
import static org.web3j.codegen.FunctionWrapperGenerator.SOLIDITY_TYPES_ARG;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.Test;
import org.web3j.TempFileProvider;
import org.web3j.utils.Strings;


public class SophiaFunctionWrapperGeneratorTest extends TempFileProvider {

    private String solidityBaseDir;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        URL url = SophiaFunctionWrapperGeneratorTest.class.getClass().getResource("/sophia");
        solidityBaseDir = url.getPath();
    }

    @Test
    public void testHumanStandardTokenGeneration() throws Exception {
//        testCodeGenerationJvmTypes("contracts", "token");
//        testCodeGenerationSolidityTypes("contracts", "token");


        //testCodeGenerationSolidityTypes("contracts", "multisig");
    }


    private void testCodeGeneration(String contractName, String inputFileName, String packageName, String types)
            throws Exception {

        //tempDirPath = "D:\\Workspace\\client-sdk-java\\codegen\\src\\test\\java";
        SophiaFunctionWrapperGenerator.main(Arrays.asList(
                types,
                solidityBaseDir + File.separator + contractName + File.separator
                        + "build" + File.separator + inputFileName + ".wasm",
                solidityBaseDir + File.separator + contractName + File.separator
                        + "build" + File.separator + inputFileName + ".cpp.abi.json",
                "-p", packageName,
                "-o", tempDirPath,
                "-t"
        ).toArray(new String[0])); // https://shipilev.net/blog/2016/arrays-wisdom-ancients/

        verifyGeneratedCode(tempDirPath + File.separator
                + packageName.replace('.', File.separatorChar) + File.separator
                + Strings.capitaliseFirstLetter(inputFileName) + ".java");
    }

    private void verifyGeneratedCode(String sourceFile) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager
                    .getJavaFileObjectsFromStrings(Arrays.asList(sourceFile));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, null, null, compilationUnits);
            boolean result = task.call();

            System.out.println(diagnostics.getDiagnostics());
            assertTrue("Generated contract contains compile time error", result);
        }
    }
}

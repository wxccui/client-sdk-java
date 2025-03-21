package org.web3j.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.AbiTypes;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.ObjectMapperFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.AbiDefinition;
import org.web3j.protocol.core.methods.response.AbiDefinition.NamedType;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.VenachainContract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.utils.*;
import org.web3j.utils.Collection;
import rx.functions.Func1;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generate Java Classes based on generated Solidity bin and abi files.
 */
public class SophiaFunctionWrapper extends Generator {

    private static final String CONTRACT_BINARY = "contractBinary";
    private static final String ABI = "ABI";
    private static final String WEB3J = "web3j";
    private static final String CREDENTIALS = "credentials";
    private static final String CONTRACT_GAS_PROVIDER = "contractGasProvider";
    private static final String TRANSACTION_MANAGER = "transactionManager";
    private static final String INITIAL_VALUE = "initialWeiValue";
    private static final String CONTRACT_ADDRESS = "contractAddressOrName";
    private static final String GAS_PRICE = "gasPrice";
    private static final String GAS_LIMIT = "gasLimit";
    private static final String FILTER = "filter";
    private static final String START_BLOCK = "startBlock";
    private static final String END_BLOCK = "endBlock";
    private static final String WEI_VALUE = "weiValue";
    private static final String FUNC_NAME_PREFIX = "FUNC_";
    private static final String ESTIMATE_GAS_FROM = "estimateGasFrom";
    private static final String ESTIMATE_GAS_TO = "estimateGasTo";
    private static final String NONCE = "nonce";

    private static final ClassName LOG = ClassName.get(Log.class);
    private static final Logger LOGGER = LoggerFactory.getLogger(SophiaFunctionWrapper.class);

    private static final String CODEGEN_WARNING = "<p>Auto generated code.\n"
            + "<p><strong>Do not modify!</strong>\n"
            + "<p>Please use the "
            + "<a href=\"https://docs.web3j.io/command_line.html\">web3j command line tools</a>,\n"
            + "or the " + SolidityFunctionWrapperGenerator.class.getName() + " in the \n"
            + "<a href=\"https://github.com/web3j/web3j/tree/master/codegen\">"
            + "codegen module</a> to update.\n";

    private final boolean useNativeJavaTypes;
    private static final String regex = "(\\w+)(?:\\[(.*?)\\])(?:\\[(.*?)\\])?";
    private static final Pattern pattern = Pattern.compile(regex);
    private final GenerationReporter reporter;

    public SophiaFunctionWrapper(boolean useNativeJavaTypes) {
        this(useNativeJavaTypes, new LogGenerationReporter(LOGGER));
    }

    SophiaFunctionWrapper(boolean useNativeJavaTypes, GenerationReporter reporter) {
        this.useNativeJavaTypes = useNativeJavaTypes;
        this.reporter = reporter;
    }

    @SuppressWarnings("unchecked")
    public void generateJavaFiles(
            String contractName, String abi, String destinationDir,
            String basePackageName)
            throws IOException, ClassNotFoundException {
        generateJavaFiles(contractName, abi,
                destinationDir, basePackageName,
                null);
    }

    void generateJavaFiles(
            String contractName, String abi, String destinationDir,
            String basePackageName, Map<String, String> addresses)
            throws IOException, ClassNotFoundException {
    	List<AbiDefinition> abiDefinitions = loadContractDefinition(abi);
    	//对abi中event对象inputs参数name的补充
    	abiDefinitions.forEach(item -> {
    		if(item.getType().equals("event")&&item.getInputs().size()>0) {
    			for(int i=0; i < item.getInputs().size(); i++) {
    				item.getInputs().get(i);
    				NamedType input = item.getInputs().get(i);
    				input.setName(createValidParamName(input.getName(), i+1));
    			}
    		}
    	});
    	
    	String className = Strings.capitaliseFirstLetter(contractName);

        TypeSpec.Builder classBuilder = createClassBuilder(className, abi);

        classBuilder.addMethod(buildConstructorWithoutGasAndPrice1(TransactionManager.class,TRANSACTION_MANAGER));
        classBuilder.addMethod(buildConstructorWithoutGasAndPrice1(Credentials.class,CREDENTIALS));
        classBuilder.addMethod(buildConstructorWithoutGasAndPrice2(TransactionManager.class,TRANSACTION_MANAGER));
        classBuilder.addMethod(buildConstructorWithoutGasAndPrice2(Credentials.class,CREDENTIALS));        

        classBuilder.addMethod(buildConstructor(Credentials.class,CREDENTIALS, false));
        classBuilder.addMethod(buildConstructor(Credentials.class,CREDENTIALS, true));
        classBuilder.addMethod(buildConstructor(TransactionManager.class,TRANSACTION_MANAGER, false));
        classBuilder.addMethod(buildConstructor(TransactionManager.class,TRANSACTION_MANAGER, true));
        classBuilder.addFields(buildFuncNameConstants(abiDefinitions));
        classBuilder.addMethods(buildFunctionDefinitions(className, classBuilder, abiDefinitions));
        classBuilder.addMethod(buildLoad(className, Credentials.class, CREDENTIALS, false));
        classBuilder.addMethod(buildLoad(className, TransactionManager.class,
                TRANSACTION_MANAGER, false));
        classBuilder.addMethod(buildLoad(className, Credentials.class, CREDENTIALS, true));
        classBuilder.addMethod(buildLoad(className, TransactionManager.class,
                TRANSACTION_MANAGER, true));

        addAddressesSupport(classBuilder, addresses);

        // 添加获取合约部署数据的方法
        classBuilder.addMethod(buildGetDeployData());
        // 添加估算合约部署Gas的方法
        classBuilder.addMethod(buildGetDeployGasLimit());

        write(basePackageName, classBuilder.build(), destinationDir);
    }

    private void addAddressesSupport(TypeSpec.Builder classBuilder,
                                     Map<String, String> addresses) {
        if (addresses != null) {

            ClassName stringType = ClassName.get(String.class);
            ClassName mapType = ClassName.get(HashMap.class);
            TypeName mapStringString = ParameterizedTypeName.get(mapType, stringType, stringType);
            FieldSpec addressesStaticField = FieldSpec
                    .builder(mapStringString, "_addresses",
                            Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
                    .build();
            classBuilder.addField(addressesStaticField);

            final CodeBlock.Builder staticInit = CodeBlock.builder();
            staticInit.addStatement("_addresses = new HashMap<String, String>()");
            addresses.forEach((k, v) ->
                    staticInit.addStatement(String.format("_addresses.put(\"%1s\", \"%2s\")",
                            k, v))
            );
            classBuilder.addStaticBlock(staticInit.build());

            // See org.web3j.tx.Contract#getStaticDeployedAddress(String)
            MethodSpec getAddress = MethodSpec
                    .methodBuilder("getStaticDeployedAddress")
                    .addModifiers(Modifier.PROTECTED)
                    .returns(stringType)
                    .addParameter(stringType, "networkId")
                    .addCode(
                            CodeBlock
                                    .builder()
                                    .addStatement("return _addresses.get(networkId)")
                                    .build())
                    .build();
            classBuilder.addMethod(getAddress);

            MethodSpec getPreviousAddress = MethodSpec
                    .methodBuilder("getPreviouslyDeployedAddress")
                    .addModifiers(Modifier.PUBLIC)
                    .addModifiers(Modifier.STATIC)
                    .returns(stringType)
                    .addParameter(stringType, "networkId")
                    .addCode(
                            CodeBlock
                                    .builder()
                                    .addStatement("return _addresses.get(networkId)")
                                    .build())
                    .build();
            classBuilder.addMethod(getPreviousAddress);

        }
    }


    private TypeSpec.Builder createClassBuilder(String className, String abi) {

        String javadoc = CODEGEN_WARNING + getWeb3jVersion();

        return TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc(javadoc)
                .superclass(VenachainContract.class)
                .addField(createAbiDefinition(abi));
    }

    private String getWeb3jVersion() {
        String version;

        try {
            // This only works if run as part of the web3j command line tools which contains
            // a version.properties file
            version = Version.getVersion();
        } catch (IOException | NullPointerException e) {
            version = Version.DEFAULT;
        }
        return "\n<p>Generated with web3j version " + version + ".\n";
    }

    private FieldSpec createBinaryDefinition(String binary) {
        return FieldSpec.builder(String.class, CONTRACT_BINARY)
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC)
                .initializer("$S", binary)
                .build();
    }
    
    private FieldSpec createAbiDefinition(String abi) {
        return FieldSpec.builder(String.class, ABI)
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC)
                .initializer("$S", abi)
                .build();
    }

    private FieldSpec createEventDefinition(
            String name,
            List<NamedTypeName> parameters) {

        CodeBlock initializer = buildVariableLengthEventInitializer(
                name, parameters);

        return FieldSpec.builder(Event.class, buildEventDefinitionName(name))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer)
                .build();
    }

    private String buildEventDefinitionName(String eventName) {
        return eventName.toUpperCase() + "_EVENT";
    }

    private List<MethodSpec> buildFunctionDefinitions(
            String className,
            TypeSpec.Builder classBuilder,
            List<AbiDefinition> functionDefinitions) throws ClassNotFoundException {

        List<MethodSpec> methodSpecs = new ArrayList<>();
        boolean constructor = false;

        for (AbiDefinition functionDefinition : functionDefinitions) {
            if (functionDefinition.getType().equals("function")) {
                MethodSpec ms = buildFunction(functionDefinition);
                methodSpecs.add(ms);

                ms = buildFunctionWithNonce(functionDefinition);
                if(ms!=null){
                    methodSpecs.add(ms);
                }
                ms = buildTransactionFunctionData(functionDefinition);
                if(ms!=null){
                    methodSpecs.add(ms);
                }
                ms = buildTransactionFunctionGasLimit(functionDefinition);
                if(ms!=null){
                    methodSpecs.add(ms);
                }
                if (functionDefinition.isConstant()) {
                    ms = buildConstTransactionFunction(functionDefinition);
                    if(ms!=null){
                        methodSpecs.add(ms);
                    }
                }
            } else if (functionDefinition.getType().equals("event")) {
                methodSpecs.addAll(buildEventFunctions(functionDefinition, classBuilder));

            } else if (functionDefinition.getType().equals("constructor")) {
                constructor = true;
                methodSpecs.add(buildDeploy(
                        className, functionDefinition, Credentials.class, CREDENTIALS, true));
                methodSpecs.add(buildDeploy(
                        className, functionDefinition, TransactionManager.class,
                        TRANSACTION_MANAGER, true));
                methodSpecs.add(buildDeploy(
                        className, functionDefinition, Credentials.class, CREDENTIALS, false));
                methodSpecs.add(buildDeploy(
                        className, functionDefinition, TransactionManager.class,
                        TRANSACTION_MANAGER, false));
            }
        }

        // constructor will not be specified in ABI file if its empty
        if (!constructor) {
            MethodSpec.Builder credentialsMethodBuilder =
                    getDeployMethodSpec(className, Credentials.class, CREDENTIALS,
                            false, true);
            methodSpecs.add(buildDeployNoParams(
                    credentialsMethodBuilder, className, CREDENTIALS,
                    false, true));

            MethodSpec.Builder credentialsMethodBuilderNoGasProvider =
                    getDeployMethodSpec(className, Credentials.class, CREDENTIALS,
                            false, false);
            methodSpecs.add(buildDeployNoParams(
                    credentialsMethodBuilderNoGasProvider, className, CREDENTIALS,
                    false, false));

            MethodSpec.Builder transactionManagerMethodBuilder =
                    getDeployMethodSpec(
                            className, TransactionManager.class, TRANSACTION_MANAGER,
                            false, true);
            methodSpecs.add(buildDeployNoParams(
                    transactionManagerMethodBuilder, className, TRANSACTION_MANAGER,
                    false, true));

            MethodSpec.Builder transactionManagerMethodBuilderNoGasProvider =
                    getDeployMethodSpec(
                            className, TransactionManager.class, TRANSACTION_MANAGER,
                            false, false);
            methodSpecs.add(buildDeployNoParams(
                    transactionManagerMethodBuilderNoGasProvider, className, TRANSACTION_MANAGER,
                    false, false));
        }

        return methodSpecs;
    }

    Iterable<FieldSpec> buildFuncNameConstants(List<AbiDefinition> functionDefinitions) {
        List<FieldSpec> fields = new ArrayList<>();
        Set<String> fieldNames = new HashSet<>();
        fieldNames.add(VenachainContract.FUNC_DEPLOY);

        for (AbiDefinition functionDefinition : functionDefinitions) {
            if (functionDefinition.getType().equals("function")) {
                String funcName = functionDefinition.getName();

                if (!fieldNames.contains(funcName)) {
                    FieldSpec field = FieldSpec.builder(String.class,
                            funcNameToConst(funcName),
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                            .initializer("$S", funcName)
                            .build();
                    fields.add(field);
                    fieldNames.add(funcName);
                }
            }
        }
        return fields;
    }

    private static MethodSpec buildConstructor(Class authType, String authName,
                                               boolean withGasProvider) {
        MethodSpec.Builder toReturn = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PROTECTED)
                .addParameter(String.class, CONTRACT_BINARY)
                .addParameter(String.class, CONTRACT_ADDRESS)
                .addParameter(Web3j.class, WEB3J)
                .addParameter(authType, authName);

        if (withGasProvider) {
            toReturn.addParameter(ContractGasProvider.class, CONTRACT_GAS_PROVIDER)
                    .addStatement("super($N, $N, $N, $N, $N)",
                            CONTRACT_BINARY, CONTRACT_ADDRESS, WEB3J, authName, CONTRACT_GAS_PROVIDER);
        } else {
            toReturn.addParameter(BigInteger.class, GAS_PRICE)
                    .addParameter(BigInteger.class, GAS_LIMIT)
                    .addStatement("super($N, $N, $N, $N, $N, $N)",
                            CONTRACT_BINARY, CONTRACT_ADDRESS, WEB3J, authName, GAS_PRICE, GAS_LIMIT)
                    .addAnnotation(Deprecated.class);
        }

        return toReturn.build();
    }
    private static MethodSpec buildConstructorWithoutGasAndPrice1(Class authType, String authName) {
        MethodSpec.Builder toReturn = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PROTECTED)
                .addParameter(String.class, CONTRACT_BINARY)
                .addParameter(String.class, CONTRACT_ADDRESS)
                .addParameter(Web3j.class, WEB3J)
                .addParameter(authType, authName);

        toReturn.addStatement("super($N, $N, $N, $N)",
        CONTRACT_BINARY, CONTRACT_ADDRESS, WEB3J, authName);
        
        return toReturn.build();
    }
    private static MethodSpec buildConstructorWithoutGasAndPrice2(Class authType, String authName) {
        MethodSpec.Builder toReturn = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PROTECTED)
                .addParameter(String.class, CONTRACT_ADDRESS)
                .addParameter(Web3j.class, WEB3J)
                .addParameter(authType, authName);

        toReturn.addStatement("super($N, $N, $N)",
                        CONTRACT_ADDRESS, WEB3J, authName);
        
        return toReturn.build();
    }

    private MethodSpec buildDeploy(
            String className, AbiDefinition functionDefinition,
            Class authType, String authName, boolean withGasProvider) {

        boolean isPayable = functionDefinition.isPayable();

        MethodSpec.Builder methodBuilder = getDeployMethodSpec(
                className, authType, authName, isPayable, withGasProvider);
        String inputParams = addParameters(methodBuilder, functionDefinition.getInputs());

        if (!inputParams.isEmpty()) {
            return buildDeployWithParams(
                    methodBuilder, className, inputParams, authName,
                    isPayable, withGasProvider);
        } else {
            return buildDeployNoParams(methodBuilder, className, authName,
                    isPayable, withGasProvider);
        }
    }

    private static MethodSpec buildDeployWithParams(
            MethodSpec.Builder methodBuilder, String className, String inputParams,
            String authName, boolean isPayable, boolean withGasProvider) {

        methodBuilder.addStatement("$T encodedConstructor = $T.encodeConstructor("
                        + "$T.<$T>asList($L)"
                        + ")",
                String.class, FunctionEncoder.class, Arrays.class, Type.class, inputParams);
        if (isPayable && !withGasProvider) {
            methodBuilder.addStatement(
                    "return deployRemoteCall($L.class, $L, $L, $L, $L, $L, encodedConstructor, $L)",
                    className, WEB3J, authName, GAS_PRICE, GAS_LIMIT, CONTRACT_BINARY, INITIAL_VALUE);
            methodBuilder.addAnnotation(Deprecated.class);
        } else if (isPayable && withGasProvider) {
            methodBuilder.addStatement(
                    "return deployRemoteCall("
                            + "$L.class, $L, $L, $L, $L, encodedConstructor, $L)",
                    className, WEB3J, authName, CONTRACT_GAS_PROVIDER, CONTRACT_BINARY, INITIAL_VALUE);
        } else if (!isPayable && !withGasProvider) {
            methodBuilder.addStatement(
                    "return deployRemoteCall($L.class, $L, $L, $L, $L, $L, encodedConstructor)",
                    className, WEB3J, authName, GAS_PRICE, GAS_LIMIT, CONTRACT_BINARY);
            methodBuilder.addAnnotation(Deprecated.class);
        } else {
            methodBuilder.addStatement(
                    "return deployRemoteCall($L.class, $L, $L, $L, $L, encodedConstructor)",
                    className, WEB3J, authName, CONTRACT_GAS_PROVIDER, CONTRACT_BINARY);
        }

        return methodBuilder.build();
    }

    private static MethodSpec buildDeployNoParams(
            MethodSpec.Builder methodBuilder, String className,
            String authName, boolean isPayable, boolean withGasPRovider) {
        if (isPayable && !withGasPRovider) {
            methodBuilder.addStatement(
                    "return deployRemoteCall($L.class, $L, $L, $L, $L, $L, $L, \"\", $L)",
                    className, WEB3J, authName, GAS_PRICE, GAS_LIMIT, CONTRACT_BINARY, ABI, INITIAL_VALUE);
            methodBuilder.addAnnotation(Deprecated.class);
        } else if (isPayable && withGasPRovider) {
            methodBuilder.addStatement(
                    "return deployRemoteCall($L.class, $L, $L, $L, $L, $L, \"\", $L)",
                    className, WEB3J, authName, CONTRACT_GAS_PROVIDER, CONTRACT_BINARY, ABI, INITIAL_VALUE);
        } else if (!isPayable && !withGasPRovider) {
            methodBuilder.addStatement(
                    "return deployRemoteCall($L.class, $L, $L, $L, $L, $L, $L, \"\")",
                    className, WEB3J, authName, GAS_PRICE, GAS_LIMIT, CONTRACT_BINARY, ABI);
            methodBuilder.addAnnotation(Deprecated.class);
        } else {
            methodBuilder.addStatement(
                    "return deployRemoteCall($L.class, $L, $L, $L, $L, $L, \"\")",
                    className, WEB3J, authName, CONTRACT_GAS_PROVIDER, CONTRACT_BINARY, ABI);
        }

        return methodBuilder.build();
    }

    private static MethodSpec.Builder getDeployMethodSpec(
            String className, Class authType, String authName,
            boolean isPayable, boolean withGasProvider) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("deploy")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(
                        buildRemoteCall(TypeVariableName.get(className, Type.class)))
                .addParameter(Web3j.class, WEB3J)
                .addParameter(authType, authName)
                .addParameter(String.class,CONTRACT_BINARY);
        if (isPayable && !withGasProvider) {
            return builder.addParameter(BigInteger.class, GAS_PRICE)
                    .addParameter(BigInteger.class, GAS_LIMIT)
                    .addParameter(BigInteger.class, INITIAL_VALUE);
        } else if (isPayable && withGasProvider) {
            return builder.addParameter(ContractGasProvider.class, CONTRACT_GAS_PROVIDER)
                    .addParameter(BigInteger.class, INITIAL_VALUE);
        } else if (!isPayable && withGasProvider) {
            return builder.addParameter(ContractGasProvider.class, CONTRACT_GAS_PROVIDER);
        } else {
            return builder.addParameter(BigInteger.class, GAS_PRICE)
                    .addParameter(BigInteger.class, GAS_LIMIT);
        }
    }

    private static MethodSpec buildLoad(
            String className, Class authType, String authName, boolean withGasProvider) {
        MethodSpec.Builder toReturn = MethodSpec.methodBuilder("load")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeVariableName.get(className, Type.class))
                .addParameter(String.class, CONTRACT_BINARY)
                .addParameter(String.class, CONTRACT_ADDRESS)
                .addParameter(Web3j.class, WEB3J)
                .addParameter(authType, authName);

        if (withGasProvider) {
            toReturn.addParameter(ContractGasProvider.class, CONTRACT_GAS_PROVIDER)
                    .addStatement("return new $L($L, $L, $L, $L, $L)", className,
                            CONTRACT_BINARY,CONTRACT_ADDRESS, WEB3J, authName, CONTRACT_GAS_PROVIDER);
        } else {
            toReturn.addParameter(BigInteger.class, GAS_PRICE)
                    .addParameter(BigInteger.class, GAS_LIMIT)
                    .addStatement("return new $L($L, $L, $L, $L, $L, $L)", className,
                            CONTRACT_BINARY, CONTRACT_ADDRESS, WEB3J, authName, GAS_PRICE, GAS_LIMIT)
                    .addAnnotation(Deprecated.class);
        }

        return toReturn.build();
    }

    /**
     * 构建获取调用合约数据的方法
     * @return
     */
    private static MethodSpec buildGetInvokeData(String functionName) {
        MethodSpec.Builder toReturn = MethodSpec.methodBuilder("getInvokeData")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(Function.class,functionName)
                .returns(String.class);

        toReturn.addStatement("return $T.invokeEncode($L)", VenachainUtil.class,functionName,functionName);
        return toReturn.build();
    }

    /**
     * 构建获取部署合约数据的方法
     * @return
     */
    private static MethodSpec buildGetDeployData() {
        MethodSpec.Builder toReturn = MethodSpec.methodBuilder("getDeployData")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(String.class,CONTRACT_BINARY)
                .returns(String.class);

        toReturn.addStatement("return $T.deployEncode($L, $L)", VenachainUtil.class,CONTRACT_BINARY,ABI);
        return toReturn.build();
    }

    /**
     * 构建估算部署合约所需Gas
     * @return
     */
    private static MethodSpec buildGetDeployGasLimit() {
        MethodSpec.Builder toReturn = MethodSpec.methodBuilder("getDeployGasLimit")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(Web3j.class,WEB3J)
                .addParameter(String.class,ESTIMATE_GAS_FROM)
                .addParameter(String.class,ESTIMATE_GAS_TO)
                .addParameter(String.class,CONTRACT_BINARY)
                .returns(BigInteger.class)
                .addException(IOException.class);
        toReturn.addStatement("return $T.estimateGasLimit($L, $L, $L, getDeployData($L))", VenachainUtil.class,WEB3J,ESTIMATE_GAS_FROM,ESTIMATE_GAS_TO,CONTRACT_BINARY);
        return toReturn.build();
    }

    String getParameterNames(List<AbiDefinition.NamedType> namedTypes) {
        List<String> inputParameterNames = buildParameterNames(namedTypes);
        return Collection.join(inputParameterNames,", ",parameterName->parameterName);
    }

    String addParameters(MethodSpec.Builder methodBuilder, List<AbiDefinition.NamedType> namedTypes) {

        List<ParameterSpec> inputParameterTypes = buildParameterTypes(namedTypes);

        List<ParameterSpec> nativeInputParameterTypes =
                new ArrayList<>(inputParameterTypes.size());
        for (ParameterSpec parameterSpec : inputParameterTypes) {
            TypeName typeName = getWrapperType(parameterSpec.type);
            nativeInputParameterTypes.add(
                    ParameterSpec.builder(typeName, parameterSpec.name).build());
        }

        methodBuilder.addParameters(nativeInputParameterTypes);

        if (useNativeJavaTypes) {
            return Collection.join(
                    inputParameterTypes,
                    ", \n",
                    // this results in fully qualified names being generated
                    this::createMappedParameterTypes);
        } else {
            return Collection.join(
                    inputParameterTypes,
                    ", ",
                    parameterSpec -> parameterSpec.name);
        }
    }

    private String createMappedParameterTypes(ParameterSpec parameterSpec) {
        if (parameterSpec.type instanceof ParameterizedTypeName) {
            List<TypeName> typeNames =
                    ((ParameterizedTypeName) parameterSpec.type).typeArguments;
            if (typeNames.size() != 1) {
                throw new UnsupportedOperationException(
                        "Only a single parameterized type is supported");
            } else {
                String parameterSpecType = parameterSpec.type.toString();
                TypeName typeName = typeNames.get(0);
                String typeMapInput = typeName + ".class";
                if (typeName instanceof ParameterizedTypeName) {
                    List<TypeName> typeArguments = ((ParameterizedTypeName) typeName).typeArguments;
                    if (typeArguments.size() != 1) {
                        throw new UnsupportedOperationException(
                                "Only a single parameterized type is supported");
                    }
                    TypeName innerTypeName = typeArguments.get(0);
                    parameterSpecType = ((ParameterizedTypeName) parameterSpec.type)
                            .rawType.toString();
                    typeMapInput = ((ParameterizedTypeName) typeName).rawType + ".class, "
                            + innerTypeName + ".class";
                }
                return "new " + parameterSpecType + "(\n"
                        + "        org.web3j.abi.Utils.typeMap("
                        + parameterSpec.name + ", " + typeMapInput + "))";
            }
        } else {
            return "new " + parameterSpec.type + "(" + parameterSpec.name + ")";
        }
    }

    private TypeName getWrapperType(TypeName typeName) {
        if (useNativeJavaTypes) {
            return getNativeType(typeName);
        } else {
            return typeName;
        }
    }

    private TypeName getWrapperRawType(TypeName typeName) {
        if (useNativeJavaTypes) {
            if (typeName instanceof ParameterizedTypeName) {
                return ClassName.get(List.class);
            }
            return getNativeType(typeName);
        } else {
            return typeName;
        }
    }

    private TypeName getIndexedEventWrapperType(TypeName typeName) {
        if (useNativeJavaTypes) {
            return getEventNativeType(typeName);
        } else {
            return typeName;
        }
    }

    static TypeName getNativeType(TypeName typeName) {

        if (typeName instanceof ParameterizedTypeName) {
            return getNativeType((ParameterizedTypeName) typeName);
        }

        String simpleName = ((ClassName) typeName).simpleName();

        if (simpleName.equals(Address.class.getSimpleName())) {
            return TypeName.get(String.class);
        } else if (simpleName.startsWith("Uint")) {
            return TypeName.get(BigInteger.class);
        } else if (simpleName.startsWith("Int")) {
            return TypeName.get(BigInteger.class);
        } else if (simpleName.equals(Utf8String.class.getSimpleName())) {
            return TypeName.get(String.class);
        } else if (simpleName.startsWith("Bytes")) {
            return TypeName.get(byte[].class);
        } else if (simpleName.equals(DynamicBytes.class.getSimpleName())) {
            return TypeName.get(byte[].class);
        } else if (simpleName.equals(Bool.class.getSimpleName())) {
            return TypeName.get(Boolean.class);  // boolean cannot be a parameterized type
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported type: " + typeName
                            + ", no native type mapping exists.");
        }
    }

    static TypeName getNativeType(ParameterizedTypeName parameterizedTypeName) {
        List<TypeName> typeNames = parameterizedTypeName.typeArguments;
        List<TypeName> nativeTypeNames = new ArrayList<>(typeNames.size());
        for (TypeName enclosedTypeName : typeNames) {
            nativeTypeNames.add(getNativeType(enclosedTypeName));
        }
        return ParameterizedTypeName.get(
                ClassName.get(List.class),
                nativeTypeNames.toArray(new TypeName[nativeTypeNames.size()]));
    }

    static TypeName getEventNativeType(TypeName typeName) {
        if (typeName instanceof ParameterizedTypeName) {
            return TypeName.get(byte[].class);
        }

        String simpleName = ((ClassName) typeName).simpleName();
        if (simpleName.equals(Utf8String.class.getSimpleName())) {
            return TypeName.get(byte[].class);
        } else {
            return getNativeType(typeName);
        }
    }

    static List<ParameterSpec> buildParameterTypes(List<AbiDefinition.NamedType> namedTypes) {
        List<ParameterSpec> result = new ArrayList<>(namedTypes.size());
        for (int i = 0; i < namedTypes.size(); i++) {
            AbiDefinition.NamedType namedType = namedTypes.get(i);

            String name = createValidParamName(namedType.getName(), i);
            String type = namedTypes.get(i).getType();

            result.add(ParameterSpec.builder(buildTypeName(type), name).build());
        }
        return result;
    }

    static List<String> buildParameterNames(List<AbiDefinition.NamedType> namedTypes) {
        List<String> result = new ArrayList<>(namedTypes.size());
        for (int i = 0; i < namedTypes.size(); i++) {
            AbiDefinition.NamedType namedType = namedTypes.get(i);
            String name = createValidParamName(namedType.getName(), i);
            result.add(name);
        }
        return result;
    }

    /**
     * Public Solidity arrays and maps require an unnamed input parameter - multiple if they
     * require a struct type.
     *
     * @param name parameter name
     * @param idx  parameter index
     * @return non-empty parameter name
     */
    static String createValidParamName(String name, int idx) {
        if (null == name || name.equals("")) {
            return "param" + idx;
        } else {
            return name;
        }
    }

    static List<TypeName> buildTypeNames(List<AbiDefinition.NamedType> namedTypes) {
        List<TypeName> result = new ArrayList<>(namedTypes.size());
        for (AbiDefinition.NamedType namedType : namedTypes) {
            result.add(buildTypeName(namedType.getType()));
        }
        return result;
    }

    MethodSpec buildFunction(
            AbiDefinition functionDefinition) throws ClassNotFoundException {
        String functionName = functionDefinition.getName();

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder(functionName)
                        .addModifiers(Modifier.PUBLIC);

        String inputParams = addParameters(methodBuilder, functionDefinition.getInputs());

        List<TypeName> outputParameterTypes = buildTypeNames(functionDefinition.getOutputs());
        if (functionDefinition.isConstant()) {
            buildConstantFunction(
                    functionDefinition, methodBuilder, outputParameterTypes, inputParams);
        } else {
            buildTransactionFunction(
                    functionDefinition, methodBuilder, inputParams);
        }

        return methodBuilder.build();
    }

    MethodSpec buildFunctionWithNonce(
            AbiDefinition functionDefinition) throws ClassNotFoundException {
        String functionName0 = functionDefinition.getName();
        String functionName = functionName0 + "WithNonce";
        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder(functionName)
                        .addModifiers(Modifier.PUBLIC);

        String inputParams = addParameters(methodBuilder, functionDefinition.getInputs());

        //List<TypeName> outputParameterTypes = buildTypeNames(functionDefinition.getOutputs());
        if (!functionDefinition.isConstant()){
            buildTransactionFunctionWithNonce(
                    functionName0,functionDefinition, methodBuilder, inputParams);
        }else{
            return null;
        }

        return methodBuilder.build();
    }


    private void buildConstantFunction(
            AbiDefinition functionDefinition,
            MethodSpec.Builder methodBuilder,
            List<TypeName> outputParameterTypes,
            String inputParams) throws ClassNotFoundException {

        String functionName = functionDefinition.getName();

        if (outputParameterTypes.isEmpty()) {
            methodBuilder.addStatement("throw new RuntimeException"
                    + "(\"cannot call constant function with void return type\")");
        } else if (outputParameterTypes.size() == 1) {

            TypeName typeName = outputParameterTypes.get(0);
            TypeName nativeReturnTypeName;
            if (useNativeJavaTypes) {
                nativeReturnTypeName = getWrapperRawType(typeName);
            } else {
                nativeReturnTypeName = getWrapperType(typeName);
            }
            methodBuilder.returns(buildRemoteCall(nativeReturnTypeName));

            methodBuilder.addStatement("final $T function = "
                            + "new $T($N, \n$T.<$T>asList($L), "
                            + "\n$T.<$T<?>>asList(new $T<$T>() {}))",
                    Function.class, Function.class, funcNameToConst(functionName),
                    Arrays.class, Type.class, inputParams,
                    Arrays.class, TypeReference.class,
                    TypeReference.class, typeName);

            if (useNativeJavaTypes) {
                if (nativeReturnTypeName.equals(ClassName.get(List.class))) {
                    // We return list. So all the list elements should
                    // also be converted to native types
                    TypeName listType = ParameterizedTypeName.get(List.class, Type.class);

                    CodeBlock.Builder callCode = CodeBlock.builder();
                    callCode.addStatement(
                            "$T result = "
                                    + "($T) executeCallSingleValueReturn(function, $T.class)",
                            listType, listType, nativeReturnTypeName);
                    callCode.addStatement("return convertToNative(result)");

                    TypeSpec callableType = TypeSpec.anonymousClassBuilder("")
                            .addSuperinterface(ParameterizedTypeName.get(
                                    ClassName.get(Callable.class), nativeReturnTypeName))
                            .addMethod(MethodSpec.methodBuilder("call")
                                    .addAnnotation(Override.class)
                                    .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                                            .addMember("value", "$S", "unchecked")
                                            .build())
                                    .addModifiers(Modifier.PUBLIC)
                                    .addException(Exception.class)
                                    .returns(nativeReturnTypeName)
                                    .addCode(callCode.build())
                                    .build())
                            .build();

                    methodBuilder.addStatement("return new $T(\n$L)",
                            buildRemoteCall(nativeReturnTypeName), callableType);
                } else {
                    methodBuilder.addStatement(
                            "return executeRemoteCallSingleValueReturn(function, $T.class)",
                            nativeReturnTypeName);
                }
            } else {
                methodBuilder.addStatement("return executeRemoteCallSingleValueReturn(function)");
            }
        } else {
            List<TypeName> returnTypes = buildReturnTypes(outputParameterTypes);

            ParameterizedTypeName parameterizedTupleType = ParameterizedTypeName.get(
                    ClassName.get(
                            "org.web3j.tuples.generated",
                            "Tuple" + returnTypes.size()),
                    returnTypes.toArray(
                            new TypeName[returnTypes.size()]));

            methodBuilder.returns(buildRemoteCall(parameterizedTupleType));

            buildVariableLengthReturnFunctionConstructor(
                    methodBuilder, functionName, inputParams, outputParameterTypes);

            buildTupleResultContainer(methodBuilder, parameterizedTupleType, outputParameterTypes);
        }
    }

    private static ParameterizedTypeName buildRemoteCall(TypeName typeName) {
        return ParameterizedTypeName.get(
                ClassName.get(RemoteCall.class), typeName);
    }

    private void buildTransactionFunction(
            AbiDefinition functionDefinition,
            MethodSpec.Builder methodBuilder,
            String inputParams) throws ClassNotFoundException {

        if (functionDefinition.hasOutputs()) {
            //CHECKSTYLE:OFF
            reporter.report(String.format(
                    "Definition of the function %s returns a value but is not defined as a view function. "
                            + "Please ensure it contains the view modifier if you want to read the return value",
                    functionDefinition.getName()));
            //CHECKSTYLE:ON
        }

        if (functionDefinition.isPayable()) {
            methodBuilder.addParameter(BigInteger.class, WEI_VALUE);
        }

        String functionName = functionDefinition.getName();

        methodBuilder.returns(buildRemoteCall(TypeName.get(TransactionReceipt.class)));

        methodBuilder.addStatement("final $T function = new $T(\n$N, \n$T.<$T>asList($L), \n$T"
                        + ".<$T<?>>emptyList())",
                Function.class, Function.class, funcNameToConst(functionName),
                Arrays.class, Type.class, inputParams, Collections.class,
                TypeReference.class);
        if (functionDefinition.isPayable()) {
            methodBuilder.addStatement(
                    "return executeRemoteCallTransaction(function, $N)", WEI_VALUE);
        } else {
            methodBuilder.addStatement("return executeRemoteCallTransaction(function)");
        }
    }

    private void buildTransactionFunctionWithNonce(
            String funcName,
            AbiDefinition functionDefinition,
            MethodSpec.Builder methodBuilder,
            String inputParams) throws ClassNotFoundException {

        if (functionDefinition.hasOutputs()) {
            //CHECKSTYLE:OFF
            reporter.report(String.format(
                    "Definition of the function %s returns a value but is not defined as a view function. "
                            + "Please ensure it contains the view modifier if you want to read the return value",
                    functionDefinition.getName()));
            //CHECKSTYLE:ON
        }

        if (functionDefinition.isPayable()) {
            methodBuilder.addParameter(BigInteger.class, WEI_VALUE);
        }

        methodBuilder.addParameter(BigInteger.class, NONCE);

        methodBuilder.returns(buildRemoteCall(TypeName.get(TransactionReceipt.class)));

        methodBuilder.addStatement("final $T function = new $T(\n$N, \n$T.<$T>asList($L), \n$T"
                        + ".<$T<?>>emptyList())",
                Function.class, Function.class, funcNameToConst(funcName),
                Arrays.class, Type.class, inputParams, Collections.class,
                TypeReference.class);
        
        methodBuilder.addStatement("return executeRemoteCallTransactionWithNonce(function, $N)", NONCE);
        
    }

    private MethodSpec buildTransactionFunctionData(AbiDefinition functionDefinition) {
        if (functionDefinition.hasOutputs()) {
            //CHECKSTYLE:OFF
            reporter.report(String.format(
                    "Definition of the function %s returns a value but is not defined as a view function. "
                            + "Please ensure it contains the view modifier if you want to read the return value",
                    functionDefinition.getName()));
            //CHECKSTYLE:ON
        }

        String functionName = functionDefinition.getName()+"Data";
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(functionName)
                .addModifiers(Modifier.PUBLIC,Modifier.STATIC);
        String inputParams = addParameters(methodBuilder, functionDefinition.getInputs());

        functionName = functionDefinition.getName();
        methodBuilder.returns(String.class);
        if (functionDefinition.isConstant()){
            // only support single return value
            TypeName typeName = buildTypeNames(functionDefinition.getOutputs()).get(0);
            methodBuilder.addStatement("final $T function = "
            + "new $T($N, \n$T.<$T>asList($L), "
            + "\n$T.<$T<?>>asList(new $T<$T>() {}))",
            Function.class, Function.class, funcNameToConst(functionName),
            Arrays.class, Type.class, inputParams,
            Arrays.class, TypeReference.class,
            TypeReference.class, typeName);

        } else {
            methodBuilder.addStatement("final $T function = new $T(\n$N, \n$T.<$T>asList($L), \n$T"
            + ".<$T<?>>emptyList())",
            Function.class, Function.class, funcNameToConst(functionName),
            Arrays.class, Type.class, inputParams, Collections.class,
            TypeReference.class);
        }
        methodBuilder.addStatement("return $T.invokeEncode(function)", VenachainUtil.class);
        return methodBuilder.build();

    }

    private MethodSpec buildConstTransactionFunction(AbiDefinition functionDefinition) {
        if (functionDefinition.hasOutputs()) {
            //CHECKSTYLE:OFF
            reporter.report(String.format(
                    "Definition of the function %s returns a value but is not defined as a view function. "
                            + "Please ensure it contains the view modifier if you want to read the return value",
                    functionDefinition.getName()));
            //CHECKSTYLE:ON
        }

        String functionName = functionDefinition.getName()+"Function";
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(functionName)
                .addModifiers(Modifier.PUBLIC,Modifier.STATIC);
        String inputParams = addParameters(methodBuilder, functionDefinition.getInputs());
        functionName = functionDefinition.getName();
        methodBuilder.returns(Function.class);
        TypeName typeName = buildTypeNames(functionDefinition.getOutputs()).get(0);
        methodBuilder.addStatement("final $T function = "
        + "new $T($N, \n$T.<$T>asList($L), "
        + "\n$T.<$T<?>>asList(new $T<$T>() {}))",
        Function.class, Function.class, funcNameToConst(functionName),
        Arrays.class, Type.class, inputParams,
        Arrays.class, TypeReference.class,
        TypeReference.class, typeName);

        methodBuilder.addStatement("return function");
        return methodBuilder.build();

    }

    private MethodSpec buildTransactionFunctionGasLimit(AbiDefinition functionDefinition) {
        if (functionDefinition.hasOutputs()) {
            //CHECKSTYLE:OFF
            reporter.report(String.format(
                    "Definition of the function %s returns a value but is not defined as a view function. "
                            + "Please ensure it contains the view modifier if you want to read the return value",
                    functionDefinition.getName()));
            //CHECKSTYLE:ON
        }

        if (!functionDefinition.isConstant()) {
            String functionName = functionDefinition.getName()+"GasLimit";
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(functionName)
                    .addModifiers(Modifier.PUBLIC,Modifier.STATIC)
                    .addParameter(Web3j.class,WEB3J)
                    .addParameter(String.class,ESTIMATE_GAS_FROM)
                    .addParameter(String.class,ESTIMATE_GAS_TO)
                    .addException(IOException.class);
            addParameters(methodBuilder, functionDefinition.getInputs());

            methodBuilder.returns(BigInteger.class);
            methodBuilder.addStatement("String ethEstimateGasData = $N($L)",functionDefinition.getName()+"Data",getParameterNames(functionDefinition.getInputs()));
            methodBuilder.addStatement("return $T.estimateGasLimit($L,$L,$L,ethEstimateGasData)", VenachainUtil.class,WEB3J,ESTIMATE_GAS_FROM,ESTIMATE_GAS_TO);
            return methodBuilder.build();
        }
        return null;
    }

    TypeSpec buildEventResponseObject(
            String className,
            List<org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName> indexedParameters,
            List<org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName> nonIndexedParameters) {

        TypeSpec.Builder builder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC);

        builder.addField(LOG, "log", Modifier.PUBLIC);
        for (org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName
                namedType : indexedParameters) {
            TypeName typeName = getIndexedEventWrapperType(namedType.typeName);
            builder.addField(typeName, namedType.getName(), Modifier.PUBLIC);
        }

        for (org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName
                namedType : nonIndexedParameters) {
            TypeName typeName = getWrapperType(namedType.typeName);
            builder.addField(typeName, namedType.getName(), Modifier.PUBLIC);
        }

        return builder.build();
    }

    MethodSpec buildEventObservableFunction(
            String responseClassName,
            String functionName,
            List<org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName> indexedParameters,
            List<org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName> nonIndexedParameters)
            throws ClassNotFoundException {

        String generatedFunctionName =
                Strings.lowercaseFirstLetter(functionName) + "EventObservable";
        ParameterizedTypeName parameterizedTypeName = ParameterizedTypeName.get(ClassName.get(rx
                .Observable.class), ClassName.get("", responseClassName));

        MethodSpec.Builder observableMethodBuilder =
                MethodSpec.methodBuilder(generatedFunctionName)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(EthFilter.class, FILTER)
                        .returns(parameterizedTypeName);

        TypeSpec converter = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(Func1.class),
                        ClassName.get(Log.class),
                        ClassName.get("", responseClassName)))
                .addMethod(MethodSpec.methodBuilder("call")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(Log.class, "log")
                        .returns(ClassName.get("", responseClassName))
                        .addStatement("$T eventValues = extractEventParametersWithLog("
                                        + buildEventDefinitionName(functionName) + ", log)",
                                VenachainContract.EventValuesWithLog.class)
                        .addStatement("$1T typedResponse = new $1T()",
                                ClassName.get("", responseClassName))
                        .addCode(buildTypedResponse("typedResponse", indexedParameters,
                                nonIndexedParameters, true))
                        .addStatement("return typedResponse")
                        .build())
                .build();

        observableMethodBuilder
                .addStatement("return web3j.ethLogObservable(filter).map($L)", converter);

        return observableMethodBuilder
                .build();
    }

    MethodSpec buildDefaultEventObservableFunction(
            String responseClassName,
            String functionName) {

        String generatedFunctionName =
                Strings.lowercaseFirstLetter(functionName) + "EventObservable";
        ParameterizedTypeName parameterizedTypeName = ParameterizedTypeName.get(ClassName.get(rx
                .Observable.class), ClassName.get("", responseClassName));

        MethodSpec.Builder observableMethodBuilder =
                MethodSpec.methodBuilder(generatedFunctionName)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(DefaultBlockParameter.class, START_BLOCK)
                        .addParameter(DefaultBlockParameter.class, END_BLOCK)
                        .returns(parameterizedTypeName);

        observableMethodBuilder.addStatement("$1T filter = new $1T($2L, $3L, "
                + "getContractAddress())", EthFilter.class, START_BLOCK, END_BLOCK)
                .addStatement("filter.addSingleTopic($T.encode("
                        + buildEventDefinitionName(functionName) + "))", EventEncoder.class)
                .addStatement("return " + generatedFunctionName + "(filter)");

        return observableMethodBuilder
                .build();
    }

    MethodSpec buildEventTransactionReceiptFunction(
            String responseClassName,
            String functionName,
            List<NamedTypeName> indexedParameters,
            List<NamedTypeName> nonIndexedParameters) {

        ParameterizedTypeName parameterizedTypeName = ParameterizedTypeName.get(
                ClassName.get(List.class), ClassName.get("", responseClassName));

        String generatedFunctionName = "get" + Strings.capitaliseFirstLetter(functionName)
                + "Events";
        MethodSpec.Builder transactionMethodBuilder = MethodSpec
                .methodBuilder(generatedFunctionName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TransactionReceipt.class, "transactionReceipt")
                .returns(parameterizedTypeName);

        transactionMethodBuilder.addStatement("$T valueList = extractEventParametersWithLog("
                + buildEventDefinitionName(functionName) + ", "
                + "transactionReceipt)", ParameterizedTypeName.get(List.class,
                		VenachainContract.EventValuesWithLog.class))
                .addStatement("$1T responses = new $1T(valueList.size())",
                        ParameterizedTypeName.get(ClassName.get(ArrayList.class),
                                ClassName.get("", responseClassName)))
                .beginControlFlow("for ($T eventValues : valueList)",
                		VenachainContract.EventValuesWithLog.class)
                .addStatement("$1T typedResponse = new $1T()",
                        ClassName.get("", responseClassName))
                .addCode(buildTypedResponse("typedResponse", indexedParameters,
                        nonIndexedParameters, false))
                .addStatement("responses.add(typedResponse)")
                .endControlFlow();


        transactionMethodBuilder.addStatement("return responses");
        return transactionMethodBuilder.build();
    }

    List<MethodSpec> buildEventFunctions(
            AbiDefinition functionDefinition,
            TypeSpec.Builder classBuilder) throws ClassNotFoundException {
        String functionName = functionDefinition.getName();
        List<AbiDefinition.NamedType> inputs = functionDefinition.getInputs();
        String responseClassName = Strings.capitaliseFirstLetter(functionName) + "EventResponse";

        List<NamedTypeName> parameters = new ArrayList<>();
        List<NamedTypeName> indexedParameters = new ArrayList<>();
        List<NamedTypeName> nonIndexedParameters = new ArrayList<>();

        for (AbiDefinition.NamedType namedType : inputs) {
            NamedTypeName parameter = new NamedTypeName(
                    namedType.getName(),
                    buildTypeName(namedType.getType()),
                    namedType.isIndexed()
            );
            if (namedType.isIndexed()) {
                indexedParameters.add(parameter);
            } else {
                nonIndexedParameters.add(parameter);
            }
            parameters.add(parameter);
        }

        classBuilder.addField(createEventDefinition(functionName, parameters));

        classBuilder.addType(buildEventResponseObject(responseClassName, indexedParameters,
                nonIndexedParameters));

        List<MethodSpec> methods = new ArrayList<>();
        methods.add(buildEventTransactionReceiptFunction(responseClassName,
                functionName, indexedParameters, nonIndexedParameters));

        methods.add(buildEventObservableFunction(responseClassName, functionName,
                indexedParameters, nonIndexedParameters));
        methods.add(buildDefaultEventObservableFunction(responseClassName,
                functionName));
        return methods;
    }

    CodeBlock buildTypedResponse(
            String objectName,
            List<org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName> indexedParameters,
            List<org.web3j.codegen.SophiaFunctionWrapper.NamedTypeName> nonIndexedParameters,
            boolean observable) {
        String nativeConversion;

        if (useNativeJavaTypes) {
            nativeConversion = ".getValue()";
        } else {
            nativeConversion = "";
        }

        CodeBlock.Builder builder = CodeBlock.builder();
        if (observable) {
            builder.addStatement("$L.log = log", objectName);
        } else {
            builder.addStatement(
                    "$L.log = eventValues.getLog()",
                    objectName);
        }
        for (int i = 0; i < indexedParameters.size(); i++) {
            builder.addStatement(
                    "$L.$L = ($T) eventValues.getIndexedValues().get($L)" + nativeConversion,
                    objectName,
                    indexedParameters.get(i).getName(),
                    getIndexedEventWrapperType(indexedParameters.get(i).getTypeName()),
                    i);
        }

        for (int i = 0; i < nonIndexedParameters.size(); i++) {
            builder.addStatement(
                    "$L.$L = ($T) eventValues.getNonIndexedValues().get($L)" + nativeConversion,
                    objectName,
                    nonIndexedParameters.get(i).getName(),
                    getWrapperType(nonIndexedParameters.get(i).getTypeName()),
                    i);
        }
        return builder.build();
    }

    static TypeName buildTypeName(String typeDeclaration) {
        String type = trimStorageDeclaration(typeDeclaration);
        Matcher matcher = pattern.matcher(type);
        if (matcher.find()) {
            Class<?> baseType = AbiTypes.getType(matcher.group(1));
            String firstArrayDimension = matcher.group(2);
            String secondArrayDimension = matcher.group(3);

            TypeName typeName;

            if ("".equals(firstArrayDimension)) {
                typeName = ParameterizedTypeName.get(DynamicArray.class, baseType);
            } else {
                Class<?> rawType = getStaticArrayTypeReferenceClass(firstArrayDimension);
                typeName = ParameterizedTypeName.get(rawType, baseType);
            }

            if (secondArrayDimension != null) {
                if ("".equals(secondArrayDimension)) {
                    return ParameterizedTypeName.get(ClassName.get(DynamicArray.class), typeName);
                } else {
                    Class<?> rawType = getStaticArrayTypeReferenceClass(secondArrayDimension);
                    return ParameterizedTypeName.get(ClassName.get(rawType), typeName);
                }
            }

            return typeName;
        } else {
            Class<?> cls = AbiTypes.getType(type);
            return ClassName.get(cls);
        }
    }

    private static Class<?> getStaticArrayTypeReferenceClass(String type) {
        try {
            return Class.forName("org.web3j.abi.datatypes.generated.StaticArray" + type);
        } catch (ClassNotFoundException e) {
            // Unfortunately we can't encode it's length as a type if it's > 32.
            return StaticArray.class;
        }
    }

    private static String trimStorageDeclaration(String type) {
        if (type.endsWith(" storage") || type.endsWith(" memory")) {
            return type.split(" ")[0];
        } else {
            return type;
        }
    }

    private List<TypeName> buildReturnTypes(List<TypeName> outputParameterTypes) {
        List<TypeName> result = new ArrayList<>(outputParameterTypes.size());
        for (TypeName typeName : outputParameterTypes) {
            result.add(getWrapperType(typeName));
        }
        return result;
    }

    private static void buildVariableLengthReturnFunctionConstructor(
            MethodSpec.Builder methodBuilder, String functionName, String inputParameters,
            List<TypeName> outputParameterTypes) throws ClassNotFoundException {

        List<Object> objects = new ArrayList<>();
        objects.add(Function.class);
        objects.add(Function.class);
        objects.add(funcNameToConst(functionName));

        objects.add(Arrays.class);
        objects.add(Type.class);
        objects.add(inputParameters);

        objects.add(Arrays.class);
        objects.add(TypeReference.class);
        for (TypeName outputParameterType : outputParameterTypes) {
            objects.add(TypeReference.class);
            objects.add(outputParameterType);
        }

        String asListParams = Collection.join(
                outputParameterTypes,
                ", ",
                typeName -> "new $T<$T>() {}");

        methodBuilder.addStatement("final $T function = new $T($N, \n$T.<$T>asList($L), \n$T"
                + ".<$T<?>>asList("
                + asListParams + "))", objects.toArray());
    }

    private void buildTupleResultContainer(
            MethodSpec.Builder methodBuilder, ParameterizedTypeName tupleType,
            List<TypeName> outputParameterTypes)
            throws ClassNotFoundException {

        List<TypeName> typeArguments = tupleType.typeArguments;

        CodeBlock.Builder tupleConstructor = CodeBlock.builder();
        tupleConstructor.addStatement(
                "$T results = executeCallMultipleValueReturn(function)",
                ParameterizedTypeName.get(List.class, Type.class))
                .add("return new $T(", tupleType)
                .add("$>$>");

        String resultStringSimple = "\n($T) results.get($L)";
        if (useNativeJavaTypes) {
            resultStringSimple += ".getValue()";
        }

        String resultStringNativeList =
                "\nconvertToNative(($T) results.get($L).getValue())";

        int size = typeArguments.size();
        ClassName classList = ClassName.get(List.class);

        for (int i = 0; i < size; i++) {
            TypeName param = outputParameterTypes.get(i);
            TypeName convertTo = typeArguments.get(i);

            String resultString = resultStringSimple;

            // If we use native java types we need to convert
            // elements of arrays to native java types too
            if (useNativeJavaTypes && param instanceof ParameterizedTypeName) {
                ParameterizedTypeName oldContainer = (ParameterizedTypeName)param;
                ParameterizedTypeName newContainer = (ParameterizedTypeName)convertTo;
                if (newContainer.rawType.compareTo(classList) == 0
                        && newContainer.typeArguments.size() == 1) {
                    convertTo = ParameterizedTypeName.get(classList,
                            oldContainer.typeArguments.get(0));
                    resultString = resultStringNativeList;
                }
            }

            tupleConstructor
                    .add(resultString, convertTo, i);
            tupleConstructor.add(i < size - 1 ? ", " : ");\n");
        }
        tupleConstructor.add("$<$<");

        TypeSpec callableType = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(Callable.class), tupleType))
                .addMethod(MethodSpec.methodBuilder("call")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addException(Exception.class)
                        .returns(tupleType)
                        .addCode(tupleConstructor.build())
                        .build())
                .build();

        methodBuilder.addStatement(
                "return new $T(\n$L)", buildRemoteCall(tupleType), callableType);
    }

    private static CodeBlock buildVariableLengthEventInitializer(
            String eventName,
            List<NamedTypeName> parameterTypes) {

        List<Object> objects = new ArrayList<>();
        objects.add(Event.class);
        objects.add(eventName);

        objects.add(Arrays.class);
        objects.add(TypeReference.class);
        for (NamedTypeName parameterType : parameterTypes) {
            objects.add(TypeReference.class);
            objects.add(parameterType.getTypeName());
        }

        String asListParams = parameterTypes.stream()
                .map(type -> {
                    if (type.isIndexed()) {
                        return "new $T<$T>(true) {}";
                    } else {
                        return "new $T<$T>() {}";
                    }
                })
                .collect(Collectors.joining(", "));

        return CodeBlock.builder()
                .addStatement("new $T($S, \n"
                        + "$T.<$T<?>>asList(" + asListParams + "))", objects.toArray())
                .build();
    }

    private List<AbiDefinition> loadContractDefinition(String abi) throws IOException {
        ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();
        AbiDefinition[] abiDefinition = objectMapper.readValue(abi, AbiDefinition[].class);
        return Arrays.asList(abiDefinition);
    }

    private static String funcNameToConst(String funcName) {
        return FUNC_NAME_PREFIX + funcName.toUpperCase();
    }

    private static class NamedTypeName {
        private final TypeName typeName;
        private final String name;
        private final boolean indexed;

        NamedTypeName(String name, TypeName typeName, boolean indexed) {
            this.name = name;
            this.typeName = typeName;
            this.indexed = indexed;
        }

        public String getName() {
            return name;
        }

        public TypeName getTypeName() {
            return typeName;
        }

        public boolean isIndexed() {
            return indexed;
        }
    }

}

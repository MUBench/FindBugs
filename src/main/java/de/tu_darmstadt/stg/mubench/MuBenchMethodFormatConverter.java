package de.tu_darmstadt.stg.mubench;

import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class converts from method representation in Findbugs to the method representation in MUBench
 * @author govind singh
 *
 */
public class MuBenchMethodFormatConverter {
	
	/**
	 * Pattern for Primitive types. 
	 * B->byte, C->char, D->double, F->float, I->int, J->long, S->short, Z->boolean
	 * Since even the parameter name can begin with any of this reserved alphabet when
	 * parameter is a custom class, the immediate next alphabet being a lowercase letter
	 * differentiate it from these basic types.
	 */
	private static final String BASICTYPEPATTERN = "(?<!\\[)[BCDFIJSZ](?![a-z])";
	/**
	 * pattern is [B for byte array, [C for char array etc.
	 */
	private static final String BASICTYPEARRAYPATTERN = "\\[[BCDFIJSZ](?![a-z])";
	/**
	 * pattern is Ljava/lang/String
	 */
	private static final String STRINGPATTERN = ".*(?<!\\[)Ljava/lang/String$";
	/**
	 * pattern is [Ljava/lang/String
	 */
	private static final String STRINGARRAYPATTERN = ".*\\[Ljava/lang/String$";
	/**
	 * pattern is Lcom/company/package/Type
	 */
	private static final String CUSTOMTYPEPATTERN = ".*(?<!\\[)L.*(?<!String)$";
	/**
	 * pattern is [Lcom/company/package/Type
	 */
	private static final String CUSTOMTYPEARRAYPATTERN = ".*\\[L.*(?<!String)$";
	/**
	 * tracks the parameter order within a ';' separated types
	 */
	private static final int INTRAORDEROFFSET = 50;
	/**
	 * tracks the parameter order within types in complete signature 
	 */
	private static final int INTERORDEROFFSET = 100;
	/**
	 * builds the eventual converted string as per MUBench expectation
	 */
	private StringBuilder finalParams;
	private Pattern pattern = null;
	private Matcher matcher = null;
	/**
	 * Key-> Position of the type in method signature, helps in the eventual sorting
	 * Value-> Type
	 */
	private Map<Integer, String> typeAndPositionMap = null;

	/**
	 * an Enum of Primitive types and their corresponding string representation.
	 * Helps in converting from Primitive type representation
	 * to the representation expected by MUBench
	 * @author govind singh
	 *
	 */
	public enum PrimitiveTypeRepresentation {

		BYTE(
			FindBugsConstants.TYPEBYTEASCHAR,
			FindBugsConstants.TYPEBYTEASSTRING),
		CHARACTER(
			FindBugsConstants.TYPECHARACTERASCHAR,
			FindBugsConstants.TYPECHARACTERASSTRING),
		DOUBLE(
			FindBugsConstants.TYPEDOUBLEASCHAR,
			FindBugsConstants.TYPEDOUBLEASSTRING),
		FLOAT(
			FindBugsConstants.TYPEFLOATASCHAR,
			FindBugsConstants.TYPEFLOATASSTRING),
		INT(
			FindBugsConstants.TYPEINTASCHAR, 
			FindBugsConstants.TYPEINTASSTRING), 
		LONG(
			FindBugsConstants.TYPELONGASCHAR,
			FindBugsConstants.TYPELONGASSTRING), 
		SHORT(
			FindBugsConstants.TYPESHORTASCHAR,
			FindBugsConstants.TYPESHORTASSTRING),	
		BOOLEAN(
			FindBugsConstants.TYPEBOOLEANASCHAR,
			FindBugsConstants.TYPEBOOLEANASSTRING);

		private char typeAsChar;
		private String typeAsString;

		PrimitiveTypeRepresentation(char typeInChar, String typeInString) {
			this.typeAsChar = typeInChar;
			this.typeAsString = typeInString;
		}

		public String getStringType() {
			return typeAsString;
		}

		/**
		 * https://stackoverflow.com/questions/604424/lookup-enum-by-string-
		 * value
		 */
		public static PrimitiveTypeRepresentation convertToPrimitiveType(char basicType) {
			for (PrimitiveTypeRepresentation t : PrimitiveTypeRepresentation.values()) {
				if (t.typeAsChar == basicType)
					return t;
			}
			return null;
		}

	}

	/**
	 * adds <K,V> pair to the map, explicitly handles if the parameter is of array type
	 * @param param the parameter type
	 * @param isArray {@code true} if the parameter an array, {@code false} otherwise
	 * @param location the location of parameter in the method signature
	 */
	private void addToStringBuilder(String param, boolean isArray, int location) {
		if (isArray){
			param = param + "[]";
		}
		param = param + ", ";
		typeAndPositionMap.put(location, param);
	}
	
	/**
	 * Extracts the basic type using {@link PrimitiveTypeRepresentation}
	 * @param basicType the basic type in Findbugs format
	 * @param isArray {@code true} if the parameter an array, {@code false} otherwise
	 * @param location the location of parameter in the method signature
	 */
	private void extractBasicType(String basicType, boolean isArray, int location) {
		char c = (isArray) ? basicType.charAt(1) : basicType.charAt(0);
		PrimitiveTypeRepresentation typeCharacters = PrimitiveTypeRepresentation.convertToPrimitiveType(c);
		if (typeCharacters != null){
			addToStringBuilder(typeCharacters.getStringType(), isArray, location);
		}
	}

	/**
	 * Extracts String type
	 * @param stringType the string type in Findbugs format
	 * @param isArray {@code true} if the parameter an array, {@code false} otherwise
	 * @param location the location of parameter in the method signature
	 */
	private void extractStringType(String stringType, boolean isArray, int location) {
		int startIndex = stringType.lastIndexOf('/') + 1;
		String extractedString = stringType.substring(startIndex);
		addToStringBuilder(extractedString, isArray, location + INTRAORDEROFFSET);
	}
	
	/**
	 * Extracts Custom type
	 * @param customType the custom type in Findbugs format
	 * @param isArray {@code true} if the parameter an array, {@code false} otherwise
	 * @param location the location of parameter in the method signature
	 */
	private void extractCustomType(String customType, boolean isArray, int location) {
		int startIndex = customType.lastIndexOf('/') + 1;
		String extractedString = customType.substring(startIndex);
		int dollarIndex = extractedString.indexOf('$');
		if (dollarIndex != -1){
			extractedString = extractedString.substring(dollarIndex + 1);
		}
		addToStringBuilder(extractedString, isArray, location + INTRAORDEROFFSET);
	}
	
	// for identifying false positive of basic types, for eg: "[SLorg/test/govind/SType;"
	// where expected o/p is: "short, SType" and not "short, short, Stype"
	private boolean isFalsePositiveBasic(String type, int index){
		String prefix = type.substring(0, index);
		if (prefix == null || prefix.length() == 0)
			return false;
		if (prefix.contains("/"))
			return true;
		else 
			return false;
	}
	
	/**
	 * Helper method that does pattern matching and calls the appropriate extractor
	 * @param type the parameter type
	 * @param regex the regex used for pattern matching
	 * @param location the location for order
	 */
	private void extractType(String type, String regex, int location){
		pattern = Pattern.compile(regex);
		matcher = pattern.matcher(type);
		while (matcher.find()) {
			String matchedString = matcher.group();
			if (regex.equals(STRINGARRAYPATTERN)){
				extractStringType(matchedString, true, location);
			}else if (regex.equals(STRINGPATTERN)){
				extractStringType(matchedString, false, location);
			}else if (regex.equals(CUSTOMTYPEARRAYPATTERN)){
				extractCustomType(matchedString, true, location);
			}else if (regex.equals(CUSTOMTYPEPATTERN)){
				extractCustomType(matchedString, false, location);
			}else if (regex.equals(BASICTYPEARRAYPATTERN)){
				if (!isFalsePositiveBasic(type, matcher.start())){
					extractBasicType(matchedString, true, location + matcher.start());
				}
			}else if (regex.equals(BASICTYPEPATTERN)){
				if (!isFalsePositiveBasic(type, matcher.start())){
					extractBasicType(matchedString, false, location + matcher.start());
				}
			}else{
				System.out.println("not a string");
			}
		}
	}
	
	/**
	 * Converts from the method representation in Findbugs to method representation in MUBench 
	 * @param findbugsSignature the method signature in Findbugs
	 * @return the converted method signature as required by MuBench
	 */
	public String convert(String findbugsSignature) {
		finalParams = new StringBuilder();
		typeAndPositionMap = new TreeMap<Integer, String>();
		if (findbugsSignature.charAt(0) != '(')
			return null;
		String params = null;
		params = findbugsSignature.substring(1, findbugsSignature.indexOf(')'));
		if (params.length() < 1)
			return params;
		String[] indivTypes = params.split(";");
		for (int i = 0; i < indivTypes.length; i++) {
			extractType(indivTypes[i], STRINGARRAYPATTERN, i * INTERORDEROFFSET);
			extractType(indivTypes[i], STRINGPATTERN, i * INTERORDEROFFSET);
			extractType(indivTypes[i], CUSTOMTYPEARRAYPATTERN, i * INTERORDEROFFSET);
			extractType(indivTypes[i], CUSTOMTYPEPATTERN, i * INTERORDEROFFSET);
			extractType(indivTypes[i], BASICTYPEARRAYPATTERN, i * INTERORDEROFFSET);
			extractType(indivTypes[i], BASICTYPEPATTERN, i * INTERORDEROFFSET);
		}
		// sort the keys and add the values to the builder
		for (Entry<Integer, String> entry : typeAndPositionMap.entrySet()) {
			finalParams.append(entry.getValue());
        }
		// remove the trailing ", "
		if(finalParams.length() > 0){
			finalParams.setLength(finalParams.length() - 2);
		}
		return finalParams.toString();
	}

}

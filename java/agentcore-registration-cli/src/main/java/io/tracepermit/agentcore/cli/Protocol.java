package io.tracepermit.agentcore.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Private length-prefixed frames. All frames and EOF validate before any JDBC effect. */
final class Protocol {
  static final String PROFILE="tracepermit-local-synthetic-registration-v1";
  static final int CONFIG_BYTES=65_536;
  static final int AUTH_BYTES=16_391;
  static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxNameLength(64)
          .maxStringLength(16_384).maxNumberLength(32).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  record Input(OperatorConfig config, String authorization, byte[] body) {
    @Override public String toString() { return "CliInput[redacted]"; }
  }

  static Input read(InputStream source, String command) {
    try {
      var stream=new DataInputStream(source);
      byte[] config=frame(stream,CONFIG_BYTES);
      String authorization=null; byte[] body=null;
      if (command.equals("register-once")) {
        authorization=utf8(frame(stream,AUTH_BYTES));
        body=frame(stream,AgentCoreRegistrationIngress.MAXIMUM_REQUEST_BYTES);
        request(body);
      }
      if (stream.read()!=-1) throw new CliFailure("INPUT_INVALID");
      return new Input(OperatorConfig.parse(object(config)),authorization,body);
    } catch (CliFailure failure) { throw failure; }
    catch (Exception failure) { throw new CliFailure("INPUT_INVALID"); }
  }

  private static byte[] frame(DataInputStream input,int maximum) throws java.io.IOException {
    int length=input.readInt();
    if(length<1||length>maximum)throw new CliFailure("INPUT_INVALID");
    byte[] value=input.readNBytes(length);
    if(value.length!=length)throw new CliFailure("INPUT_INVALID");
    return value;
  }

  static String utf8(byte[] bytes) {
    try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
    catch(Exception invalid){throw new CliFailure("INPUT_INVALID");}
  }

  static ObjectNode object(byte[] bytes) {
    try {
      JsonNode value=JSON.readTree(utf8(bytes));
      if(value==null||!value.isObject())throw new CliFailure("INPUT_INVALID");
      return (ObjectNode)value;
    }catch(CliFailure failure){throw failure;}
    catch(Exception invalid){throw new CliFailure("INPUT_INVALID");}
  }

  static void fields(JsonNode node,Set<String> required,Set<String> optional) {
    if(node==null||!node.isObject())throw new CliFailure("CONFIG_INVALID");
    var names=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(names::add);
    if(!names.containsAll(required)||names.stream().anyMatch(x->!required.contains(x)&&!optional.contains(x)))
      throw new CliFailure("CONFIG_INVALID");
  }

  private static void request(byte[] bytes) {
    try {
      var request=object(bytes);
      fields(request,Set.of("schemaVersion","clientRequestKey","title"),Set.of("body"));
      if(!"1.0".equals(requestText(request,"schemaVersion",3))
          ||!requestText(request,"clientRequestKey",128).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
        throw new CliFailure("INPUT_INVALID");
      requestText(request,"title",256);
      if(request.has("body"))requestText(request,"body",16_384);
    }catch(RuntimeException failure){throw new CliFailure("INPUT_INVALID");}
  }

  static String text(JsonNode node,String key,int maximum) {
    String text=requestText(node,key,maximum);
    if(text.chars().anyMatch(Character::isISOControl))throw new CliFailure("CONFIG_INVALID");
    return text;
  }
  private static String requestText(JsonNode node,String key,int maximum) {
    var value=node.get(key);
    if(value==null||!value.isTextual())throw new CliFailure("CONFIG_INVALID");
    String text=value.textValue();
    if(text.isBlank()||text.length()>maximum)
      throw new CliFailure("CONFIG_INVALID");
    for(int i=0;i<text.length();i++){
      char c=text.charAt(i);
      if(Character.isHighSurrogate(c)){
        if(++i==text.length()||!Character.isLowSurrogate(text.charAt(i)))throw new CliFailure("CONFIG_INVALID");
      }else if(Character.isLowSurrogate(c))throw new CliFailure("CONFIG_INVALID");
    }
    return text;
  }
  static long number(JsonNode node,String key,long min,long max) {
    var n=node.get(key);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong())throw new CliFailure("CONFIG_INVALID");
    long value=n.longValue();if(value<min||value>max)throw new CliFailure("CONFIG_INVALID");return value;
  }
}

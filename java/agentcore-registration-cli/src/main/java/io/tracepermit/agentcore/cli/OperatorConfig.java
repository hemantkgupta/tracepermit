package io.tracepermit.agentcore.cli;

import static io.tracepermit.agentcore.cli.Protocol.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Closed trusted LOCAL operator frame. Parsing is offline and creates no datasource/driver. */
final class OperatorConfig {
  final OidcProviderConfiguration provider;
  final RSAPublicKey key;
  final String keyId;
  final AgentCoreRegistrationIngress.Enrollment enrollment;
  final Database replay;
  final Database operations;
  final long capacity;
  final Duration dmlBudget;

  private OperatorConfig(OidcProviderConfiguration provider,RSAPublicKey key,String keyId,
      AgentCoreRegistrationIngress.Enrollment enrollment,Database replay,Database operations,long capacity,Duration budget){
    this.provider=provider;this.key=key;this.keyId=keyId;this.enrollment=enrollment;
    this.replay=replay;this.operations=operations;this.capacity=capacity;this.dmlBudget=budget;
  }

  static OperatorConfig parse(JsonNode root) {
    try {
      fields(root,Set.of("schemaVersion","profile","provider","enrollment","target","replay","operations",
          "replayCapacity","replayDmlMillis"),Set.of());
      require("1.0".equals(text(root,"schemaVersion",3))&&PROFILE.equals(text(root,"profile",64)));
      JsonNode p=root.get("provider"),e=root.get("enrollment"),t=root.get("target");
      fields(p,Set.of("issuer","audience","keyId","algorithm","jwtType","publicKeySpki","tenantClaim",
          "tenantValue","tenantId","clientId","maximumTokenLifetimeSeconds","keyCacheSeconds",
          "refreshCooldownSeconds","jwksUri","metadataUri","origin","supportedScopes"),Set.of());
      require("RS256".equals(text(p,"algorithm",8))&&"at+jwt".equals(text(p,"jwtType",16))
          &&"CLI".equals(text(p,"origin",8)));
      String encoded=text(p,"publicKeySpki",4096);
      byte[] der=Base64.getDecoder().decode(encoded);
      require(Base64.getEncoder().encodeToString(der).equals(encoded));
      var publicKey=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
      require(publicKey.getModulus().bitLength()>=2048&&publicKey.getModulus().bitLength()<=4096);
      String kid=text(p,"keyId",256);require(kid.matches("[A-Za-z0-9][A-Za-z0-9._:@/+=-]{0,255}"));
      String issuer=text(p,"issuer",512),client=text(p,"clientId",256),tenant=text(p,"tenantId",128);
      require(issuer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
          <=io.tracepermit.persistence.identity.JdbcWorkloadIdentityReplayStore.MAX_ISSUER_LENGTH);
      var scopes=new HashSet<ConnectorScope>();var inputScopes=p.get("supportedScopes");
      require(inputScopes!=null&&inputScopes.isArray()&&inputScopes.size()>0&&inputScopes.size()<=16);
      for(var scope:inputScopes){require(scope.isTextual()&&scope.textValue().matches("[a-z][a-z0-9._:-]{0,127}"));
        require(scopes.add(ConnectorScope.of(scope.textValue())));}
      require(scopes.contains(AgentCoreRegistrationIngress.REQUIRED_SCOPE));
      var policy=new OidcProviderConfiguration(URI.create(issuer),text(p,"audience",512),
          URI.create(text(p,"jwksUri",512)),URI.create(text(p,"metadataUri",512)),List.of(URI.create(issuer)),
          "Local synthetic registration",Map.of(client,"Locally enrolled synthetic client"),text(p,"tenantClaim",256),
          Map.of(text(p,"tenantValue",256),tenant),scopes,ConnectorOrigin.CLI,
          Duration.ofSeconds(number(p,"maximumTokenLifetimeSeconds",1,900)),
          Duration.ofSeconds(number(p,"keyCacheSeconds",1,900)),
          Duration.ofSeconds(number(p,"refreshCooldownSeconds",1,900)));
      fields(e,Set.of("verifiedSubjectId","tenantId","workspaceId","requesterId","clientId",
          "retentionPolicyId","retentionSeconds"),Set.of());
      require(tenant.equals(text(e,"tenantId",128))&&client.equals(text(e,"clientId",256)));
      fields(t,Set.of("adapterId","environment","recoveryGeneration","policyId","policyVersion","policyContentHash",
          "artifactDigests","installationId","repositoryId","ownerId","repository","head","base","headSha","baseSha"),Set.of());
      var artifacts=t.get("artifactDigests");require(artifacts!=null&&artifacts.isObject()&&artifacts.size()>0&&artifacts.size()<=16);
      Map<String,String> digests=new HashMap<>();
      artifacts.fieldNames().forEachRemaining(name->{require(name.length()<=128);digests.put(name,text(artifacts,name,71));});
      long safe=9_007_199_254_740_991L;
      var target=new GitHubPilotTargetBinding(number(t,"installationId",1,safe),number(t,"repositoryId",1,safe),
          number(t,"ownerId",1,safe),text(t,"repository",256),text(t,"head",255),text(t,"base",255),
          text(t,"headSha",40),text(t,"baseSha",40));
      var profile=new GitHubBusinessOperationBinding.ExpectedProfile(text(t,"adapterId",64),text(t,"environment",16),
          number(t,"recoveryGeneration",1,safe),new GitHubBusinessOperationBinding.PolicyRelease(text(t,"policyId",256),
              number(t,"policyVersion",1,safe),text(t,"policyContentHash",71)),digests,target);
      var scope=new BusinessOperationScope(tenant,text(e,"workspaceId",256),text(e,"requesterId",256));
      var enrollment=new AgentCoreRegistrationIngress.Enrollment(issuer,text(e,"verifiedSubjectId",1024),client,scope,
          profile,text(e,"retentionPolicyId",256),Duration.ofSeconds(number(e,"retentionSeconds",1,31_536_000)));
      var replay=Database.parse(root.get("replay"));var operations=Database.parse(root.get("operations"));
      require(replay.port()!=operations.port()||!replay.catalog().equals(operations.catalog())
          ||!replay.engine().equals(operations.engine()));
      return new OperatorConfig(policy,publicKey,kid,enrollment,replay,
          operations,number(root,"replayCapacity",1,1_000_000),
          Duration.ofMillis(number(root,"replayDmlMillis",1,10_000)));
    } catch(RuntimeException invalid){throw new CliFailure("CONFIG_INVALID");}
    catch(Exception invalid){throw new CliFailure("CONFIG_INVALID");}
  }

  OidcAccessTokenVerifier verifier(Clock clock) {
    return new OidcAccessTokenVerifier(issuer->provider.issuer().toString().equals(issuer)?Optional.of(provider):Optional.empty(),
        (policy,kid,algorithm,now)->{
          if(!provider.equals(policy)||!keyId.equals(kid)||!"RS256".equals(algorithm))throw new CliFailure("REJECTED");
          return key;
        },clock);
  }

  record Database(String engine,int port,String catalog,String user,String password) {
    static Database parse(JsonNode n){
      fields(n,Set.of("engine","host","port","catalog","user","password"),Set.of());
      String engine=text(n,"engine",32);require(Set.of("POSTGRESQL_16_4","MARIADB_11_4_13","MARIADB_10_6_25").contains(engine));
      require("127.0.0.1".equals(text(n,"host",16)));
      String catalog=text(n,"catalog",63),user=text(n,"user",63);
      require(catalog.matches("[A-Za-z][A-Za-z0-9_]{0,62}")&&user.matches("[A-Za-z][A-Za-z0-9_]{0,62}"));
      return new Database(engine,(int)number(n,"port",1,65535),catalog,user,text(n,"password",256));
    }
    @Override public String toString(){return "LoopbackDatabase[redacted]";}
  }
  private static void require(boolean valid){if(!valid)throw new CliFailure("CONFIG_INVALID");}
  @Override public String toString(){return "LocalSyntheticOperatorConfig[redacted]";}
}

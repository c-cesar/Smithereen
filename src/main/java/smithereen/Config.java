package smithereen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.admin.UserRole;
import smithereen.storage.sql.SQLQueryBuilder;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.util.PublicSuffixList;
import smithereen.util.TopLevelDomainList;
import spark.utils.StringUtils;

public class Config{
	public static String dbHost;
	public static String dbUser;
	public static String dbPassword;
	public static String dbName;
	public static int dbMaxConnections;

	public static String domain;

	public static String serverIP;
	public static int serverPort;

	public static File uploadPath;
	public static File mediaCachePath;
	public static long mediaCacheMaxSize;
	public static long mediaCacheFileSizeLimit;
	public static boolean useHTTP;
	public static String staticFilesPath;
	public static String uploadUrlPath;
	public static String mediaCacheUrlPath;
	public static final boolean DEBUG=System.getProperty("smithereen.debug")!=null || System.getenv("SMITHEREEN_DEBUG")!=null;

	public static String imgproxyLocalUploads;
	public static String imgproxyLocalMediaCache;
	public static String imgproxyUrl;
	public static byte[] imgproxyKey;
	public static byte[] imgproxySalt;

	private static URI localURI;

	public static StorageBackend storageBackend;
	public static S3Configuration s3Configuration;

	// following fields are kept in the config table in database and some are configurable from /settings/admin

	public static int dbSchemaVersion;
	public static String serverDisplayName;
	public static String serverDescription;
	public static String serverShortDescription;
	public static String serverPolicy;
	public static String serverAdminEmail;
	public static SignupMode signupMode=SignupMode.CLOSED;
	public static boolean signupConfirmEmail;
	public static boolean signupFormUseCaptcha;

	public static String mailFrom;
	public static String smtpServerAddress;
	public static int smtpPort;
	public static String smtpUsername;
	public static String smtpPassword;
	public static boolean smtpUseTLS;

	public static PrivateKey serviceActorPrivateKey;
	public static PublicKey serviceActorPublicKey;
	public static byte[] objectIdObfuscationKey;
	public static int[][] objectIdObfuscationKeysByType=new int[ObfuscatedObjectIDType.values().length][];
	public static byte[] emailUnsubscribeKey;

	public static Map<Integer, UserRole> userRoles=new HashMap<>();

	private static final Logger LOG=LoggerFactory.getLogger(Config.class);

	public static void load(String filePath) throws IOException{
		Properties props=new Properties();
		try(FileInputStream in=new FileInputStream(filePath)){
			props.load(in);
		}

		dbHost=props.getProperty("db.host");
		dbUser=props.getProperty("db.user");
		dbPassword=props.getProperty("db.password");
		dbName=props.getProperty("db.name");
		dbMaxConnections=Utils.parseIntOrDefault(props.getProperty("db.max_connections"), 100);

		domain=props.getProperty("domain");

		uploadPath=new File(props.getProperty("upload.path"));
		mediaCachePath=new File(props.getProperty("media_cache.path"));
		mediaCacheMaxSize=Utils.parseFileSize(props.getProperty("media_cache.max_size"));
		mediaCacheFileSizeLimit=Utils.parseFileSize(props.getProperty("media_cache.file_size_limit"));
		uploadUrlPath=props.getProperty("upload.url_path");
		mediaCacheUrlPath=props.getProperty("media_cache.url_path");

		useHTTP=Boolean.parseBoolean(props.getProperty("use_http_scheme.i_know_what_i_am_doing", "false"));
		localURI=URI.create("http"+(useHTTP ? "" : "s")+"://"+domain+"/");

		serverIP=props.getProperty("server.ip", "127.0.0.1");
		serverPort=Utils.parseIntOrDefault(props.getProperty("server.port", "4567"), 4567);
		staticFilesPath=props.getProperty("web.static_files_path");

		imgproxyUrl=props.getProperty("imgproxy.url_prefix");
		imgproxyLocalUploads=props.getProperty("imgproxy.local_uploads");
		imgproxyLocalMediaCache=props.getProperty("imgproxy.local_media_cache");
		imgproxyKey=Utils.hexStringToByteArray(props.getProperty("imgproxy.key"));
		imgproxySalt=Utils.hexStringToByteArray(props.getProperty("imgproxy.salt"));
		if(imgproxyUrl.charAt(0)!='/')
			imgproxyUrl='/'+imgproxyUrl;

		storageBackend=switch(props.getProperty("upload.backend", "local")){
			case "local" -> StorageBackend.LOCAL;
			case "s3" -> StorageBackend.S3;
			default -> throw new IllegalStateException("Unexpected value for `upload.backend`: " + props.getProperty("upload.backend"));
		};

		if(storageBackend==StorageBackend.S3){
			s3Configuration=new S3Configuration(
					requireProperty(props, "upload.s3.key_id"),
					requireProperty(props, "upload.s3.secret_key"),
					props.getProperty("upload.s3.endpoint"),
					props.getProperty("upload.s3.region", "us-east-1"),
					requireProperty(props, "upload.s3.bucket"),
					switch(props.getProperty("upload.s3.protocol", "https")){
						case "http" -> "http";
						case "https" -> "https";
						default -> throw new IllegalArgumentException("`upload.s3.protocol` must be either \"https\" or \"http\"");
					},
					props.getProperty("upload.s3.hostname"),
					props.getProperty("upload.s3.alias_host"),
					Boolean.parseBoolean(props.getProperty("upload.s3.override_path_style", "false"))
			);
		}
	}

	public static void loadFromDatabase() throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection(); ResultSet res=new SQLQueryBuilder(conn).selectFrom("config").allColumns().execute()){
			HashMap<String, String> dbValues=new HashMap<>();
			while(res.next()){
				dbValues.put(res.getString(1), res.getString(2));
			}
			dbSchemaVersion=Utils.parseIntOrDefault(dbValues.get("SchemaVersion"), 0);

			serverDisplayName=dbValues.get("ServerDisplayName");
			serverDescription=dbValues.get("ServerDescription");
			serverShortDescription=dbValues.get("ServerShortDescription");
			serverPolicy=dbValues.get("ServerPolicy");
			serverAdminEmail=dbValues.get("ServerAdminEmail");
			String _signupMode=dbValues.get("SignupMode");
			if(StringUtils.isNotEmpty(_signupMode)){
				try{
					signupMode=SignupMode.valueOf(_signupMode);
				}catch(IllegalArgumentException ignore){}
			}
			signupConfirmEmail="1".equals(dbValues.get("SignupConfirmEmail"));
			signupFormUseCaptcha="1".equals(dbValues.get("SignupFormUseCaptcha"));

			smtpServerAddress=dbValues.getOrDefault("Mail_SMTP_ServerAddress", "127.0.0.1");
			smtpPort=Utils.parseIntOrDefault(dbValues.get("Mail_SMTP_ServerPort"), 25);
			mailFrom=dbValues.getOrDefault("MailFrom", "noreply@"+domain);
			smtpUsername=dbValues.get("Mail_SMTP_Username");
			smtpPassword=dbValues.get("Mail_SMTP_Password");
			smtpUseTLS=Utils.parseIntOrDefault(dbValues.get("Mail_SMTP_UseTLS"), 0)==1;

			String pkey=dbValues.get("ServiceActorPrivateKey");
			try{
				if(pkey==null){
					KeyPairGenerator kpg=KeyPairGenerator.getInstance("RSA");
					kpg.initialize(2048);
					KeyPair pair=kpg.generateKeyPair();
					PrivateKey priv=pair.getPrivate();
					PublicKey pub=pair.getPublic();
					updateInDatabase(Map.of(
							"ServiceActorPrivateKey", Base64.getEncoder().encodeToString(priv.getEncoded()),
							"ServiceActorPublicKey", Base64.getEncoder().encodeToString(pub.getEncoded())
					));
					serviceActorPrivateKey=priv;
					serviceActorPublicKey=pub;
				}else{
					EncodedKeySpec spec=new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkey));
					serviceActorPrivateKey=KeyFactory.getInstance("RSA").generatePrivate(spec);
					spec=new X509EncodedKeySpec(Base64.getDecoder().decode(dbValues.get("ServiceActorPublicKey")));
					serviceActorPublicKey=KeyFactory.getInstance("RSA").generatePublic(spec);
				}
			}catch(NoSuchAlgorithmException|InvalidKeySpecException ignore){}

			String obfKey=dbValues.get("ObjectIdObfuscationKey");
			if(obfKey==null){
				byte[] key=new byte[16];
				new SecureRandom().nextBytes(key);
				updateInDatabase("ObjectIdObfuscationKey", Base64.getEncoder().encodeToString(key));
				objectIdObfuscationKey=key;
			}else{
				objectIdObfuscationKey=Base64.getDecoder().decode(obfKey);
			}
			try{
				MessageDigest md=MessageDigest.getInstance("SHA-256");
				for(ObfuscatedObjectIDType type:ObfuscatedObjectIDType.values()){
					ByteArrayOutputStream buf=new ByteArrayOutputStream();
					buf.write(objectIdObfuscationKey);
					buf.write(type.toString().getBytes(StandardCharsets.UTF_8));
					DataInputStream in=new DataInputStream(new ByteArrayInputStream(md.digest(buf.toByteArray())));
					int[] key=new int[4];
					for(int i=0;i<4;i++){
						key[i]=in.readInt();
					}
					objectIdObfuscationKeysByType[type.ordinal()]=key;
				}
			}catch(NoSuchAlgorithmException|IOException ignore){}

			String unsubKey=dbValues.get("EmailUnsubscribeKey");
			if(unsubKey==null){
				byte[] key=new byte[16];
				new SecureRandom().nextBytes(key);
				updateInDatabase("EmailUnsubscribeKey", Base64.getEncoder().encodeToString(key));
				emailUnsubscribeKey=key;
			}else{
				emailUnsubscribeKey=Base64.getDecoder().decode(unsubKey);
			}

			TopLevelDomainList.lastUpdatedTime=Long.parseLong(dbValues.getOrDefault("TLDList_LastUpdated", "0"));
			if(TopLevelDomainList.lastUpdatedTime>0){
				TopLevelDomainList.update(dbValues.get("TLDList_Data"));
			}

			PublicSuffixList.lastUpdatedTime=Long.parseLong(dbValues.getOrDefault("PSList_LastUpdated", "0"));
			if(PublicSuffixList.lastUpdatedTime>0){
				PublicSuffixList.update(Arrays.asList(dbValues.get("PSList_Data").split("\n")));
			}
		}
	}

	public static void updateInDatabase(String key, String value) throws SQLException{
		new SQLQueryBuilder()
				.insertInto("config")
				.value("key", key)
				.value("value", value)
				.onDuplicateKeyUpdate()
				.executeNoResult();
	}

	public static void updateInDatabase(Map<String, String> values) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=conn.prepareStatement("INSERT INTO config (`key`, `value`) VALUES "+String.join(", ", Collections.nCopies(values.size(), "(?, ?)"))+" ON DUPLICATE KEY UPDATE `value`=values(`value`)");
			int i=1;
			for(Map.Entry<String, String> e: values.entrySet()){
				stmt.setString(i, e.getKey());
				stmt.setString(i+1, e.getValue());
				i+=2;
			}
			stmt.execute();
		}
	}

	public static URI localURI(String path){
		return localURI.resolve(path);
	}

	public static boolean isLocal(URI uri){
		if(domain.contains(":")){
			return (uri.getHost()+":"+uri.getPort()).equalsIgnoreCase(domain);
		}
		return uri.getHost().equalsIgnoreCase(domain);
	}

	public static String getServerDisplayName(){
		return StringUtils.isNotEmpty(serverDisplayName) ? serverDisplayName : domain;
	}

	public static void reloadRoles() throws SQLException{
		userRoles.clear();
		userRoles=new SQLQueryBuilder()
				.selectFrom("user_roles")
				.allColumns()
				.executeAsStream(UserRole::fromResultSet)
				.collect(Collectors.toMap(UserRole::id, Function.identity()));
	}

	private static String requireProperty(Properties props, String name){
		String value=props.getProperty(name);
		if(value==null)
			throw new IllegalArgumentException("Config property `"+name+"` is required");
		return value;
	}

	public enum SignupMode{
		OPEN,
		CLOSED,
		INVITE_ONLY,
		MANUAL_APPROVAL
	}

	public enum StorageBackend{
		LOCAL,
		S3
	}

	public record S3Configuration(String keyID, String secretKey, String endpoint, String region, String bucket, String protocol, String hostname, String aliasHost, boolean overridePathStyle){}
}

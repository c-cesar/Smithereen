package smithereen.storage.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import smithereen.Config;
import smithereen.model.media.MediaFileID;
import smithereen.storage.ImgProxy;

public abstract class MediaFileStorageDriver{
	protected static final Logger LOG=LoggerFactory.getLogger(MediaFileStorageDriver.class);
	private static MediaFileStorageDriver instance;

	public abstract void storeFile(File localFile, MediaFileID id, boolean keepLocalFile, String downloadFileName) throws IOException;
	public abstract InputStream openStream(MediaFileID id) throws IOException;
	public abstract void deleteFile(MediaFileID id) throws IOException;
	public abstract ImgProxy.UrlBuilder getImgProxyURL(MediaFileID id);
	public abstract URI getFilePublicURL(MediaFileID id, String downloadFileName);

	public URI getFilePublicURL(MediaFileID id){
		return getFilePublicURL(id, null);
	}

	public void storeFile(File localFile, MediaFileID id, boolean keepLocalFile) throws IOException{
		storeFile(localFile, id, keepLocalFile, null);
	}

	public Set<MediaFileID> deleteFiles(Collection<MediaFileID> ids){
		HashSet<MediaFileID> deletedIDs=new HashSet<>();
		for(MediaFileID id:ids){
			try{
				deleteFile(id);
				deletedIDs.add(id);
			}catch(IOException x){
				LOG.warn("Failed to delete file {}", id, x);
			}
		}
		return deletedIDs;
	}

	public static MediaFileStorageDriver getInstance(){
		if(instance==null){
			instance=switch(Config.storageBackend){
				case LOCAL -> new LocalMediaFileStorageDriver();
				case S3 -> new S3MediaFileStorageDriver(Config.s3Configuration);
			};
		}
		return instance;
	}
}

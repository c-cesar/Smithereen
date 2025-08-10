package smithereen.model.reports;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.LocalImage;
import smithereen.model.MailMessage;
import smithereen.model.Post;
import smithereen.model.comments.Comment;
import smithereen.model.photos.Photo;
import smithereen.storage.MediaStorageUtils;
import smithereen.util.JsonArrayBuilder;

public sealed interface ReportableContentObject permits Post, MailMessage, Photo, Comment{
	JsonObject serializeForReport(int targetID, Set<Long> outFileIDs);
	void fillFromReport(int reportID, JsonObject jo);
	ReportableContentObjectID getReportableObjectID();

	static JsonArray serializeMediaAttachments(List<ActivityPubObject> attachments, Set<Long> outFileIDs){
		JsonArrayBuilder jb=new JsonArrayBuilder();
		for(ActivityPubObject att:attachments){
			jb.add(MediaStorageUtils.serializeAttachment(att));
			if(att instanceof LocalImage li){
				outFileIDs.add(li.fileID);
			}
		}
		return jb.build();
	}
}

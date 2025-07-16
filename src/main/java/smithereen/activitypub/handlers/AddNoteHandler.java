package smithereen.activitypub.handlers;

import java.net.URI;
import java.sql.SQLException;
import java.util.Objects;

import smithereen.Config;
import smithereen.activitypub.ActivityHandlerContext;
import smithereen.activitypub.ActivityTypeHandler;
import smithereen.activitypub.objects.ActivityPubBoardTopic;
import smithereen.activitypub.objects.ActivityPubCollection;
import smithereen.activitypub.objects.Actor;
import smithereen.activitypub.objects.ForeignActor;
import smithereen.activitypub.objects.NoteOrQuestion;
import smithereen.activitypub.objects.activities.Add;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.model.Post;
import smithereen.exceptions.BadRequestException;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.comments.CommentReplyParent;
import smithereen.model.comments.CommentableContentObject;

public class AddNoteHandler extends ActivityTypeHandler<Actor, Add, NoteOrQuestion>{
	@Override
	public void handle(ActivityHandlerContext context, Actor actor, Add activity, NoteOrQuestion post) throws SQLException{
		URI targetCollectionID;
		if(activity.target.link!=null)
			targetCollectionID=activity.target.link;
		else if(activity.target.object instanceof ActivityPubCollection collection)
			targetCollectionID=collection.activityPubID;
		else
			throw new BadRequestException("Add.target is required (either a collection ID or abbreviated collection object)");

		if(!Objects.equals(actor.getWallURL(), targetCollectionID) && !Objects.equals(actor.getWallCommentsURL(), targetCollectionID)){
			if(post.target!=null){
				if(Config.isLocal(post.activityPubID))
					return;
				CommentReplyParent replyParent;
				if(post.target instanceof ActivityPubBoardTopic)
					replyParent=context.appContext.getObjectLinkResolver().resolveNative(post.target.activityPubID, BoardTopic.class, true, true, false, actor, true);
				else if(post.inReplyTo==null)
					return;
				else
					replyParent=context.appContext.getObjectLinkResolver().resolveNative(post.inReplyTo, CommentReplyParent.class, true, true, false, actor, true);
				CommentableContentObject parent=switch(replyParent){
					case CommentableContentObject cco -> cco;
					case Comment comment -> context.appContext.getCommentsController().getCommentParentIgnoringPrivacy(comment);
				};
				URI expectedTargetID=parent.getCommentCollectionID(context.appContext);
				if(!Objects.equals(expectedTargetID, post.target.activityPubID))
					throw new BadRequestException("Target collection ID does not match expected "+expectedTargetID);
				if(parent.getOwnerID()!=actor.getOwnerID())
					throw new UserActionNotAllowedException("Collection owner does not match the actor sending the Add activity");
				Comment comment=post.asNativeComment(context.appContext);
				if(comment.id!=0)
					return;
				context.appContext.getCommentsController().putOrUpdateForeignComment(comment);
				return;
			}

			LOG.warn("Ignoring Add{Note} sent by {} because target collection {} is unknown or unsupported", actor.activityPubID, targetCollectionID);
			return;
		}
//		if(!Objects.equals(post.owner.activityPubID, actor.activityPubID))
//			throw new BadRequestException("Post's target collection doesn't match actor's wall collection");
		Post nativePost=post.asNativePost(context.appContext);
		if(post.inReplyTo!=null){
			Post topLevel=context.appContext.getWallController().getPostOrThrow(nativePost.getReplyChainElement(0));
			if(nativePost.ownerID!=topLevel.ownerID)
				throw new BadRequestException("Reply must have target set to top-level post owner's wall");
		}

		boolean isNew=nativePost.id==0;
		context.appContext.getWallController().loadAndPreprocessRemotePostMentions(nativePost, post);
		context.appContext.getObjectLinkResolver().storeOrUpdateRemoteObject(nativePost, post);
		if(isNew)
			context.appContext.getNotificationsController().createNotificationsForObject(nativePost);
	}
}

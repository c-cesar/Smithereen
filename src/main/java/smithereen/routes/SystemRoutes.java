package smithereen.routes;

import com.google.gson.JsonObject;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import smithereen.ApplicationContext;
import smithereen.BuildInfo;
import smithereen.Config;
import smithereen.LruCache;
import smithereen.Utils;
import smithereen.activitypub.ActivityPub;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.ActivityPubPhotoAlbum;
import smithereen.activitypub.objects.Actor;
import smithereen.activitypub.objects.Document;
import smithereen.activitypub.objects.Image;
import smithereen.activitypub.objects.LocalImage;
import smithereen.activitypub.objects.NoteOrQuestion;
import smithereen.controllers.ObjectLinkResolver;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.FederationException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.exceptions.RemoteObjectFetchException;
import smithereen.exceptions.UnsupportedRemoteObjectTypeException;
import smithereen.exceptions.UserErrorException;
import smithereen.lang.Lang;
import smithereen.model.Account;
import smithereen.model.ActivityPubRepresentable;
import smithereen.model.AttachmentHostContentObject;
import smithereen.model.CachedRemoteImage;
import smithereen.model.CaptchaInfo;
import smithereen.model.ForeignGroup;
import smithereen.model.ForeignUser;
import smithereen.model.Group;
import smithereen.model.MailMessage;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.OwnedContentObject;
import smithereen.model.Poll;
import smithereen.model.PollOption;
import smithereen.model.Post;
import smithereen.model.ServerRule;
import smithereen.model.admin.ViolationReport;
import smithereen.model.board.BoardTopic;
import smithereen.model.groups.GroupLink;
import smithereen.model.media.PhotoViewerInlineData;
import smithereen.model.reports.ReportableContentObject;
import smithereen.model.SessionInfo;
import smithereen.model.SizedImage;
import smithereen.model.User;
import smithereen.model.UserInteractions;
import smithereen.model.WebDeltaResponse;
import smithereen.model.attachments.GraffitiAttachment;
import smithereen.model.comments.Comment;
import smithereen.model.comments.CommentReplyParent;
import smithereen.model.comments.CommentableContentObject;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.model.util.QuickSearchResults;
import smithereen.model.viewmodel.PostViewModel;
import smithereen.storage.GroupStorage;
import smithereen.storage.MediaCache;
import smithereen.storage.MediaStorageUtils;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.templates.RenderedTemplateResponse;
import smithereen.text.TextProcessor;
import smithereen.util.CaptchaGenerator;
import smithereen.util.CharacterRange;
import smithereen.util.JsonArrayBuilder;
import smithereen.util.JsonObjectBuilder;
import smithereen.util.NamedMutexCollection;
import smithereen.util.UriBuilder;
import smithereen.util.XTEA;
import spark.QueryParamsMap;
import spark.Request;
import spark.Response;
import spark.utils.StringUtils;

import static smithereen.Utils.*;

public class SystemRoutes{
	private static final Logger LOG=LoggerFactory.getLogger(SystemRoutes.class);
	private static final NamedMutexCollection downloadMutex=new NamedMutexCollection();
	private static final String privacyPolicyHTML;

	static{
		try(InputStreamReader reader=new InputStreamReader(SystemRoutes.class.getResourceAsStream("/privacy-policy.md"))){
			Parser mdParser=new Parser.Builder().build();
			HtmlRenderer mdRenderer=new HtmlRenderer.Builder().build();
			String html=mdRenderer.render(mdParser.parseReader(reader));
			PebbleTemplate template=new PebbleEngine.Builder()
					.defaultEscapingStrategy("html")
					.build()
					.getLiteralTemplate(html);
			StringWriter writer=new StringWriter();
			template.evaluate(writer, Map.of("domain", Config.domain));
			org.jsoup.nodes.Document doc=org.jsoup.parser.Parser.parseBodyFragment(writer.toString(), "");
			doc.select("ul").forEach(el->el.addClass("actualList"));
			doc.select("li").forEach(el->{
				Element span=doc.createElement("span");
				el.childNodes().forEach(span::appendChild);
				el.appendChild(span);
			});
			doc.select("h1").forEach(el->el.tagName("h3"));
			privacyPolicyHTML=doc.body().html();
		}catch(IOException x){
			throw new RuntimeException(x);
		}
	}

	private static ActivityPubObject verifyObjectAndGetAttachment(int index, String type, Object obj){
		ActivityPubRepresentable apr=(ActivityPubRepresentable) obj;
		if(obj==null || Config.isLocal(apr.getActivityPubID())){
			LOG.warn("downloading {}: post not found or is local", type);
			return null;
		}
		List<ActivityPubObject> attachments=((AttachmentHostContentObject)obj).getAttachments();
		if(index>=attachments.size() || index<0){
			LOG.warn("downloading {}: index {} out of bounds {}", type, index, attachments.size());
			return null;
		}
		ActivityPubObject att=attachments.get(index);
		if(!(att instanceof Document)){
			LOG.warn("downloading {}: attachment {} is not a Document", type, att.getClass().getName());
			return null;
		}
		return att;
	}

	public static Object downloadExternalMedia(Request req, Response resp) throws SQLException{
		requireQueryParams(req, "type", "format", "size");
		ApplicationContext ctx=context(req);
		MediaCache cache=MediaCache.getInstance();
		String type=req.queryParams("type");
		String mime;
		URI uri=null;
		MediaCache.ItemType itemType;
		SizedImage.Type sizeType;
		SizedImage.Format format=switch(req.queryParams("format")){
			case "jpeg", "jpg" -> SizedImage.Format.JPEG;
			case "webp" -> SizedImage.Format.WEBP;
			case "png" -> {
				if("post_photo".equals(type)){
					yield SizedImage.Format.PNG;
				}else{
					throw new BadRequestException();
				}
			}
			default -> throw new BadRequestException();
		};

		sizeType=SizedImage.Type.fromSuffix(req.queryParams("size"));
		if(sizeType==null)
			return "";

		float[] cropRegion=null;
		User user=null;
		Group group=null;
		boolean isGraffiti=false;

		boolean isPostPhoto="post_photo".equals(type);

		switch(type){
			case "user_ava" -> {
				itemType=MediaCache.ItemType.AVATAR;
				mime="image/jpeg";
				int userID=parseIntOrDefault(req.queryParams("user_id"), 0);
				user=UserStorage.getById(userID);
				if(user==null || Config.isLocal(user.activityPubID)){
					LOG.warn("downloading user_ava: user {} not found or is local", userID);
					return "";
				}
				Image im=user.getBestAvatarImage();
				if(im!=null && im.url!=null){
					cropRegion=user.getAvatarCropRegion();
					uri=im.url;
					if(StringUtils.isNotEmpty(im.mediaType))
						mime=im.mediaType;
					else
						mime="image/jpeg";
				}
			}
			case "group_ava" -> {
				itemType=MediaCache.ItemType.AVATAR;
				mime="image/jpeg";
				int groupID=parseIntOrDefault(req.queryParams("group_id"), 0);
				group=GroupStorage.getById(groupID);
				if(group==null || Config.isLocal(group.activityPubID)){
					LOG.warn("downloading group_ava: group {} not found or is local", groupID);
					return "";
				}
				Image im=group.getBestAvatarImage();
				if(im!=null && im.url!=null){
					cropRegion=group.getAvatarCropRegion();
					uri=im.url;
					if(StringUtils.isNotEmpty(im.mediaType))
						mime=im.mediaType;
					else
						mime="image/jpeg";
				}
			}
			case "post_photo", "message_photo", "comment_photo" -> {
				itemType=MediaCache.ItemType.PHOTO;
				SessionInfo sess=sessionInfo(req);
				Object contentObj=switch(type){
					case "post_photo" -> {
						int postID=parseIntOrDefault(req.queryParams("post_id"), 0);
						yield ctx.getWallController().getPostOrThrow(postID);
					}
					case "message_photo" -> {
						requireQueryParams(req, "msg_id");
						if(sess==null || sess.account==null)
							yield null;
						long msgID=decodeLong(req.queryParams("msg_id"));
						yield context(req).getMailController().getMessage(sess.account.user, msgID, false);
					}
					case "comment_photo" -> {
						long id=XTEA.decodeObjectID(req.queryParams("comment_id"), ObfuscatedObjectIDType.COMMENT);
						yield ctx.getCommentsController().getCommentIgnoringPrivacy(id);
					}
					default -> throw new IllegalStateException("Unexpected value: "+type);
				};

				if(contentObj instanceof OwnedContentObject oco){
					ctx.getPrivacyController().enforceObjectPrivacy(sess==null || sess.account==null ? null : sess.account.user, oco);
				}

				int index=safeParseInt(req.queryParams("index"));
				ActivityPubObject att=verifyObjectAndGetAttachment(index, type, contentObj);
				if(att==null)
					return "";

				if(att.mediaType==null){
					if(att instanceof Image){
						mime="image/jpeg";
					}else{
						LOG.warn("downloading post_photo: media type is null and attachment type {} isn't Image", att.getClass().getName());
						return "";
					}
				}else if(!att.mediaType.startsWith("image/")){
					LOG.warn("downloading post_photo: attachment media type {} is invalid", att.mediaType);
					return "";
				}else{
					mime=att.mediaType;
				}
				if(format==SizedImage.Format.PNG && (!(att instanceof Image img) || !img.isGraffiti)){
					LOG.warn("downloading post_photo: requested png but the attachment is not a graffiti");
					throw new BadRequestException();
				}
				isGraffiti=att instanceof Image img && img.isGraffiti;
				uri=att.url;
			}
			case "album_photo" -> {
				long id=XTEA.deobfuscateObjectID(decodeLong(req.queryParams("photo_id")), ObfuscatedObjectIDType.PHOTO);
				Photo photo=ctx.getPhotosController().getPhotoIgnoringPrivacy(id);
				SessionInfo sess=sessionInfo(req);
				ctx.getPrivacyController().enforceObjectPrivacy(sess!=null && sess.account!=null ? sess.account.user : null, photo);
				uri=photo.remoteSrc;
				mime="image/webp";
				itemType=MediaCache.ItemType.PHOTO;
			}
			case "group_link" -> {
				requireQueryParams(req, "group", "link");
				int groupID=safeParseInt(req.queryParams("group"));
				long linkID=safeParseLong(req.queryParams("link"));
				Account self=currentUserAccount(req);
				Group g=ctx.getGroupsController().getGroupOrThrow(groupID);
				ctx.getPrivacyController().enforceGroupContentAccess(req, g);
				GroupLink link=ctx.getGroupsController().getLink(g, linkID);
				if(link.apImageURL==null)
					throw new BadRequestException();
				uri=link.apImageURL;
				mime="image/webp";
				itemType=MediaCache.ItemType.PHOTO;
			}
			case null, default -> {
				LOG.warn("unknown external file type {}", type);
				return "";
			}
		}

		if(uri!=null){
			final String uriStr=uri.toString();
			downloadMutex.acquire(uriStr);
			LOG.trace("downloadExternalMedia: after mutex acquire {}", uri);
			try{
				MediaCache.Item existing=cache.get(uri);
				if(mime.startsWith("image/")){
					if(existing!=null){
						LOG.debug("downloadExternalMedia: found existing {}", uri);
						resp.redirect(new CachedRemoteImage((MediaCache.PhotoItem) existing, cropRegion, uri).getUriForSizeAndFormat(sizeType, format).toString());
						return "";
					}
					try{
						SessionInfo sessionInfo=sessionInfo(req);
						if(sessionInfo==null || sessionInfo.account==null){ // Only download attachments for logged-in users. Prevents crawlers from causing unnecessary churn in the media cache
							if(req.queryParams("fb")!=null){
								boolean is2x=req.queryParams("2x")!=null;
								resp.redirect(Config.localURI(sizeType==SizedImage.Type.AVA_SQUARE_SMALL || (is2x && sizeType==SizedImage.Type.AVA_SQUARE_MEDIUM) ? "/res/broken_photo_small.svg" : "/res/broken_photo.svg").toString());
								return "";
							}
							resp.status(404);
							return "";
						}
						LOG.debug("downloadExternalMedia: downloading {}", uri);
						MediaCache.PhotoItem item;
						if(isGraffiti)
							item=(MediaCache.PhotoItem) cache.downloadAndPut(uri, mime, itemType, true, GraffitiAttachment.WIDTH, GraffitiAttachment.HEIGHT);
						else
							item=(MediaCache.PhotoItem) cache.downloadAndPut(uri, mime, itemType, false, 0, 0);
						if(item==null){
							if(itemType==MediaCache.ItemType.AVATAR && req.queryParams("retrying")==null){
								try{
									String extraParams="";
									if(req.queryParams("fb")!=null)
										extraParams+="&fb";
									if(req.queryParams("2x")!=null)
										extraParams+="&2x";
									if(user!=null){
										ForeignUser updatedUser=context(req).getObjectLinkResolver().resolve(user.activityPubID, ForeignUser.class, true, true, true);
										resp.redirect(Config.localURI("/system/downloadExternalMedia?type=user_ava&user_id="+updatedUser.id+"&size="+sizeType.suffix()+"&format="+format.fileExtension()+"&retrying"+extraParams).toString());
										return "";
									}else{
										ForeignGroup updatedGroup=context(req).getObjectLinkResolver().resolve(group.activityPubID, ForeignGroup.class, true, true, true);
										resp.redirect(Config.localURI("/system/downloadExternalMedia?type=group_ava&user_id="+updatedGroup.id+"&size="+sizeType.suffix()+"&format="+format.fileExtension()+"&retrying"+extraParams).toString());
										return "";
									}
								}catch(ObjectNotFoundException ignore){}
							}
							LOG.debug("downloadExternalMedia: all attempts failed for {}", uri);
							if(req.queryParams("fb")!=null){
								boolean is2x=req.queryParams("2x")!=null;
								resp.redirect(Config.localURI(sizeType==SizedImage.Type.AVA_SQUARE_SMALL || (is2x && sizeType==SizedImage.Type.AVA_SQUARE_MEDIUM) ? "/res/broken_photo_small.svg" : "/res/broken_photo.svg").toString());
								return "";
							}
							resp.status(404);
						}else{
							LOG.debug("downloadExternalMedia: download finished {}", uri);
							resp.redirect(new CachedRemoteImage(item, cropRegion, uri).getUriForSizeAndFormat(sizeType, format).toString());
						}
						return "";
					}catch(IOException x){
						LOG.debug("Exception while downloading external media file from {}", uri, x);
					}
					if(req.queryParams("fb")!=null){
						boolean is2x=req.queryParams("2x")!=null;
						resp.redirect(Config.localURI(sizeType==SizedImage.Type.AVA_SQUARE_SMALL || (is2x && sizeType==SizedImage.Type.AVA_SQUARE_MEDIUM) ? "/res/broken_photo_small.svg" : "/res/broken_photo.svg").toString());
						return "";
					}
					resp.status(404);
				}
			}finally{
				downloadMutex.release(uriStr);
			}
		}
		return "";
	}

	public static Object uploadPostPhoto(Request req, Response resp, Account self, ApplicationContext ctx){
		boolean isGraffiti=req.queryParams("graffiti")!=null;
		return uploadPhotoAttachment(req, resp, self, isGraffiti);
	}

	public static Object uploadMessagePhoto(Request req, Response resp, Account self, ApplicationContext ctx){
		return uploadPhotoAttachment(req, resp, self, false);
	}

	private static Object uploadPhotoAttachment(Request req, Response resp, Account self, boolean isGraffiti){
		LocalImage photo=MediaStorageUtils.saveUploadedImage(req, resp, self, isGraffiti);
		if(isAjax(req)){
			resp.type("application/json");
			PhotoViewerInlineData pvData=new PhotoViewerInlineData(0, "rawFile/"+photo.getLocalID(), photo.getURLsForPhotoViewer());
			return new JsonObjectBuilder()
					.add("id", photo.fileRecord.id().getIDForClient())
					.add("width", photo.width)
					.add("height", photo.height)
					.add("thumbs", new JsonObjectBuilder()
							.add("jpeg", photo.getUriForSizeAndFormat(SizedImage.Type.PHOTO_THUMB_SMALL, SizedImage.Format.JPEG).toString())
							.add("webp", photo.getUriForSizeAndFormat(SizedImage.Type.PHOTO_THUMB_SMALL, SizedImage.Format.WEBP).toString())
					)
					.add("pv", gson.toJsonTree(pvData)).build();
		}
		resp.redirect(back(req));
		return "";
	}

	public static Object aboutServer(Request req, Response resp) throws SQLException{
		ApplicationContext ctx=context(req);
		RenderedTemplateResponse model=new RenderedTemplateResponse("about_server", req);
		model.with("title", lang(req).get("about_server"));
		model.with("serverPolicy", Config.serverPolicy)
				.with("serverAdmins", UserStorage.getAdmins())
				.with("serverAdminEmail", Config.serverAdminEmail)
				.with("totalUsers", UserStorage.getLocalUserCount())
				.with("totalPosts", PostStorage.getLocalPostCount(false))
				.with("totalGroups", GroupStorage.getLocalGroupCount())
				.with("serverVersion", BuildInfo.VERSION)
				.with("restrictedServers", ctx.getModerationController().getAllServers(0, 10000, null, true, null).list)
				.with("serverRules", ctx.getModerationController().getServerRules());

		return model;
	}

	public static Object quickSearch(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "q");
		String query=req.queryParams("q").trim();
		QuickSearchResults res=ctx.getSearchController().quickSearch(query, self.user);
		return new RenderedTemplateResponse("quick_search_results", req).with("users", res.users()).with("groups", res.groups()).with("externalObjects", res.externalObjects()).with("avaSize", req.attribute("mobile")!=null ? 48 : 30);
	}

	public static Object loadRemoteObject(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "uri");
		String uri=req.queryParams("uri");
		resp.type("application/json");
		Lang l=lang(req);
		try{
			Object obj=ctx.getSearchController().loadRemoteObject(self.user, uri);
			if(req.queryParams("group")!=null && req.queryParams("link")!=null){
				try{
					Group group=ctx.getGroupsController().getGroupOrThrow(safeParseInt(req.queryParams("group")));
					GroupLink gl=ctx.getGroupsController().getLink(group, safeParseLong(req.queryParams("link")));
					if(gl.isUnresolvedActivityPubObject && gl.url.toString().equals(uri)){
						ctx.getGroupsController().setLinkResolved(group, gl, ObjectLinkResolver.getObjectIdFromObject(obj));
					}
				}catch(ObjectNotFoundException ignore){}
			}
			return new JsonObjectBuilder().add("success", switch(obj){
				case Post post when post.getReplyLevel()>0 -> Config.localURI("/posts/"+post.replyKey.getFirst()+"#comment"+post.id).toString();
				case Post post -> post.getInternalURL().toString();
				case Actor actor -> actor.getProfileURL();
				case PhotoAlbum album -> album.getURL();
				case Photo photo -> photo.getURL();
				case Comment comment -> ctx.getCommentsController().getCommentParent(self.user, comment).getURL();
				case BoardTopic topic -> topic.getURL();
				default -> throw new RemoteObjectFetchException(RemoteObjectFetchException.ErrorType.UNSUPPORTED_OBJECT_TYPE, null);
			}).build();
		}catch(RemoteObjectFetchException x){
			JsonObjectBuilder jb=new JsonObjectBuilder().add("error", switch(x.error){
				case UNSUPPORTED_OBJECT_TYPE -> l.get("unsupported_remote_object_type");
				case TIMEOUT -> {
					if(x.uri!=null)
						yield l.get("remote_object_timeout", Map.of("server", x.uri.getHost()));
					else
						yield l.get("remote_object_loading_error");
				}
				case NETWORK_ERROR -> l.get("remote_object_network_error");
				case NOT_FOUND -> l.get("remote_object_not_found");
				case OTHER_ERROR -> l.get("remote_object_loading_error");
			});
			if(x.getCause()!=null){
				jb.add("details", TextProcessor.escapeHTML(x.getCause().getMessage()));
			}else if(StringUtils.isNotEmpty(x.getMessage())){
				jb.add("details", TextProcessor.escapeHTML(x.getMessage()));
			}
			return jb.build();
		}
	}

	public static Object votePoll(Request req, Response resp, Account self, ApplicationContext ctx) throws SQLException{
		int id=parseIntOrDefault(req.queryParams("id"), 0);
		if(id==0)
			throw new ObjectNotFoundException();
		Poll poll=PostStorage.getPoll(id, null);
		if(poll==null)
			throw new ObjectNotFoundException();

		Actor owner;
		if(poll.ownerID>0){
			User _owner=UserStorage.getById(poll.ownerID);
			ensureUserNotBlocked(self.user, _owner);
			owner=_owner;
		}else{
			Group _owner=ctx.getGroupsController().getGroupOrThrow(-poll.ownerID);
			ensureUserNotBlocked(self.user, _owner);
			ctx.getPrivacyController().enforceUserAccessToGroupContent(self.user, _owner);
			owner=_owner;
		}

		String[] _options=req.queryMap("option").values();
		if(_options.length<1)
			throw new BadRequestException("options param is empty");
		if(_options.length!=1 && !poll.multipleChoice)
			throw new BadRequestException("invalid option count");
		if(_options.length>poll.options.size())
			throw new BadRequestException("invalid option count");
		int[] optionIDs=new int[_options.length];
		List<PollOption> options=new ArrayList<>(_options.length);
		for(int i=0;i<_options.length;i++){
			int optID=parseIntOrDefault(_options[i], 0);
			if(optID<=0)
				throw new BadRequestException("invalid option id '"+_options[i]+"'");
			PollOption option=null;
			for(PollOption opt:poll.options){
				if(opt.id==optID){
					option=opt;
					break;
				}
			}
			if(option==null)
				throw new BadRequestException("option with id "+optID+" does not exist in this poll");
			if(options.contains(option))
				throw new BadRequestException("option with id "+optID+" seen more than once");
			optionIDs[i]=optID;
			options.add(option);
		}

		if(poll.isExpired())
			return wrapError(req, resp, "err_poll_expired");

		int[] voteIDs=PostStorage.voteInPoll(self.user.id, poll.id, optionIDs);
		if(voteIDs==null)
			return wrapError(req, resp, "err_poll_already_voted");

		poll.numVoters++;
		for(PollOption opt:options)
			opt.numVotes++;

		ctx.getActivityPubWorker().sendPollVotes(self.user, poll, owner, options, voteIDs);
		int postID=PostStorage.getPostIdByPollId(id);
		Post post;
		if(postID>0){
			post=ctx.getWallController().getPostOrThrow(postID);
			post.poll=poll; // So the last vote time is as it was before the vote
			ctx.getWallController().sendUpdateQuestionIfNeeded(post);
		}else{
			post=null;
		}

		if(isAjax(req)){
			UserInteractions interactions=new UserInteractions();
			interactions.pollChoices=Arrays.stream(optionIDs).boxed().collect(Collectors.toList());
			RenderedTemplateResponse model=new RenderedTemplateResponse("poll", req).with("poll", poll).with("interactions", interactions);
			model.with("post", new PostViewModel(post));
			return new WebDeltaResponse(resp).setContent("poll"+poll.id, model.renderBlock("inner"));
		}

		resp.redirect(back(req));
		return "";
	}

	public static Object reportForm(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "type", "id");
		RenderedTemplateResponse model=new RenderedTemplateResponse("report_form", req);
		String rawID=req.queryParams("id");
		Actor actorForAvatar;
		String title, subtitle, boxTitle, titleText, otherServerDomain;
		Lang l=lang(req);
		String type=req.queryParams("type");
		switch(type){
			case "post" -> {
				int id=safeParseInt(rawID);
				Post post=ctx.getWallController().getPostOrThrow(id);
				if(post.isMastodonStyleRepost()){
					post=ctx.getWallController().getPostOrThrow(post.repostOf);
					rawID=post.id+"";
				}
				User postAuthor=ctx.getUsersController().getUserOrThrow(post.authorID);
				actorForAvatar=postAuthor;
				title=postAuthor.getCompleteName();
				subtitle=TextProcessor.truncateOnWordBoundary(post.text, 200);
				boxTitle=l.get(post.getReplyLevel()>0 ? "report_title_comment" : "report_title_post");
				titleText=l.get(post.getReplyLevel()>0 ? "report_text_comment" : "report_text_post");
				otherServerDomain=Config.isLocal(post.getActivityPubID()) ? null : post.getActivityPubID().getHost();
			}
			case "user" -> {
				int id=safeParseInt(rawID);
				User user=ctx.getUsersController().getUserOrThrow(id);
				actorForAvatar=user;
				title=user.getCompleteName();
				subtitle="";
				boxTitle=l.get("report_title_user");
				titleText=l.get("report_text_user");
				otherServerDomain=user instanceof ForeignUser fu ? fu.domain : null;
			}
			case "group" -> {
				int id=safeParseInt(rawID);
				Group group=ctx.getGroupsController().getGroupOrThrow(id);
				actorForAvatar=group;
				title=group.name;
				subtitle="";
				boxTitle=l.get(group.isEvent() ? "report_title_event" : "report_title_group");
				titleText=l.get(group.isEvent() ? "report_text_event" : "report_text_group");
				otherServerDomain=group instanceof ForeignGroup fg ? fg.domain : null;
			}
			case "message" -> {
				long id=decodeLong(rawID);
				MailMessage msg=ctx.getMailController().getMessage(self.user, id, false);
				User user=ctx.getUsersController().getUserOrThrow(msg.senderID);
				actorForAvatar=user;
				title=user.getCompleteName();
				subtitle=TextProcessor.truncateOnWordBoundary(msg.text, 200);
				boxTitle=l.get("report_title_message");
				titleText=l.get("report_text_message");
				otherServerDomain=user instanceof ForeignUser fu ? fu.domain : null;
			}
			case "photo" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.PHOTO);
				Photo photo=ctx.getPhotosController().getPhotoIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, photo);
				User user=ctx.getUsersController().getUserOrThrow(photo.authorID);
				actorForAvatar=user;
				title=user.getCompleteName();
				subtitle=photo.description;
				boxTitle=l.get("report_title_photo");
				titleText=l.get("report_text_photo");
				otherServerDomain=user instanceof ForeignUser fu ? fu.domain : null;
			}
			case "comment" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.COMMENT);
				Comment comment=ctx.getCommentsController().getCommentIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, comment);
				User user=ctx.getUsersController().getUserOrThrow(comment.authorID);
				actorForAvatar=user;
				title=user.getCompleteName();
				subtitle=TextProcessor.truncateOnWordBoundary(comment.text, 200);
				boxTitle=l.get("report_title_comment");
				titleText=l.get("report_text_comment");
				otherServerDomain=Config.isLocal(comment.getActivityPubID()) ? null : comment.getActivityPubID().getHost();
			}
			default -> throw new BadRequestException();
		}
		model.with("actorForAvatar", actorForAvatar)
				.with("reportTitle", title)
				.with("reportSubtitle", subtitle)
				.with("reportTitleText", titleText)
				.with("otherServerDomain", otherServerDomain)
				.with("serverRules", ctx.getModerationController().getServerRules());
		return wrapForm(req, resp, "report_form", "/system/submitReport?type="+type+"&id="+rawID, boxTitle, "send", model);
	}

	public static Object submitReport(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "type", "id", "reason");
		String rawID=req.queryParams("id");
		String type=req.queryParams("type");
		String comment=req.queryParamOrDefault("reportText", "");
		ViolationReport.Reason reason=enumValue(req.queryParams("reason"), ViolationReport.Reason.class);
		boolean forward="on".equals(req.queryParams("forward"));
		Set<Integer> rules;
		if(reason==ViolationReport.Reason.SERVER_RULES){
			QueryParamsMap rulesMap=req.queryMap("rules");
			if(rulesMap==null)
				throw new BadRequestException();
			Set<Integer> validRuleIDs=ctx.getModerationController()
					.getServerRules()
					.stream()
					.map(ServerRule::id)
					.collect(Collectors.toSet());
			rules=rulesMap.toMap()
					.keySet()
					.stream()
					.map(Utils::safeParseInt)
					.filter(validRuleIDs::contains)
					.collect(Collectors.toSet());
			if(rules.isEmpty())
				throw new BadRequestException();
		}else{
			rules=Set.of();
		}

		Actor target;
		List<ReportableContentObject> content;

		switch(type){
			case "post" -> {
				int id=safeParseInt(rawID);
				Post post=ctx.getWallController().getPostOrThrow(id);
				content=List.of(post);
				target=ctx.getUsersController().getUserOrThrow(post.authorID);
			}
			case "user" -> {
				int id=safeParseInt(rawID);
				target=ctx.getUsersController().getUserOrThrow(id);
				content=null;
			}
			case "group" -> {
				int id=safeParseInt(rawID);
				target=ctx.getGroupsController().getGroupOrThrow(id);
				content=null;
			}
			case "message" -> {
				long id=decodeLong(rawID);
				MailMessage msg=ctx.getMailController().getMessage(self.user, id, false);
				target=ctx.getUsersController().getUserOrThrow(msg.senderID);
				content=List.of(msg);
			}
			case "photo" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.PHOTO);
				Photo photo=ctx.getPhotosController().getPhotoIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, photo);
				target=ctx.getUsersController().getUserOrThrow(photo.authorID);
				content=List.of(photo);
			}
			case "comment" -> {
				long id=XTEA.decodeObjectID(rawID, ObfuscatedObjectIDType.COMMENT);
				Comment commentObj=ctx.getCommentsController().getCommentIgnoringPrivacy(id);
				ctx.getPrivacyController().enforceObjectPrivacy(self.user, commentObj);
				content=List.of(commentObj);
				target=ctx.getUsersController().getUserOrThrow(commentObj.authorID);
			}
			default -> throw new BadRequestException("invalid type");
		}

		ctx.getModerationController().createViolationReport(self.user, target, content, reason, rules, comment, forward);
		if(isAjax(req)){
			return new WebDeltaResponse(resp).showSnackbar(lang(req).get("report_submitted"));
		}
		return "";
	}

	public static Object captcha(Request req, Response resp) throws IOException{
		requireQueryParams(req, "sid");
		String sid=req.queryParams("sid");
		if(sid.length()>16)
			sid=sid.substring(0, 16);

		CaptchaGenerator.Captcha c=CaptchaGenerator.generate();
		LruCache<String, CaptchaInfo> captchas=req.session().attribute("captchas");
		if(captchas==null){
			captchas=new LruCache<>(10);
			req.session().attribute("captchas", captchas);
		}
		captchas.put(sid, new CaptchaInfo(c.answer(), Instant.now()));

		resp.type("image/png");
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		ImageIO.write(c.image(), "png", out);
		return out.toByteArray();
	}

	public static Object oEmbed(Request req, Response resp){
		if(!"json".equals(req.queryParamOrDefault("format", "json"))){
			resp.status(501);
			return "Unsupported format";
		}
		requireQueryParams(req, "url");
		URI url;
		try{
			url=new URI(req.queryParams("url"));
		}catch(URISyntaxException x){
			throw new BadRequestException(x);
		}
		ApplicationContext ctx=context(req);
		Post post=ctx.getObjectLinkResolver().resolveLocally(url, Post.class);
		if(!post.isLocal() || post.privacy!=Post.Privacy.PUBLIC || (post.getReplyLevel()==0 && post.ownerID!=post.authorID)){
			resp.status(401);
			return "";
		}
		if(post.getReplyLevel()>0){
			Post topLevel=ctx.getWallController().getPostOrThrow(post.replyKey.getFirst());
			if(topLevel.privacy!=Post.Privacy.PUBLIC || topLevel.ownerID!=post.authorID){
				resp.status(401);
				return "";
			}
		}
		int maxWidth=Math.max(200, safeParseInt(req.queryParamOrDefault("maxwidth", "500")));
		Map<Integer, User> users=ctx.getUsersController().getUsers(Set.of(post.authorID));
		User author=users.get(post.authorID);
		if(author==null)
			throw new ObjectNotFoundException();
		resp.type("application/json");
		RenderedTemplateResponse model=new RenderedTemplateResponse("post_embed_code", req)
				.with("post", post)
				.with("users", users);
		return new JsonObjectBuilder()
				.add("version", "1.0")
				.add("type", "rich")
				.add("width", Math.min(500, maxWidth))
				.add("height", (String)null)
				.add("author_name", author.getFullName())
				.add("author_url", UriBuilder.local().rawPath(author.getFullUsername()).build().toString())
				.add("provider_name", Config.serverDisplayName)
				.add("provider_url", "https://"+Config.domain+"/")
				.add("html", model.renderToString())
				.build();
	}

	public static Object redirectForRemoteInteraction(Request req, Response resp){
		requireQueryParams(req, "contentURL", "domain");
		String contentURL=req.queryParams("contentURL");
		String domain=req.queryParams("domain");
		if(domain.startsWith("@"))
			domain=domain.substring(1);
		String realDomain, username;
		if(domain.startsWith("https://") || domain.startsWith("http://")){
			URI uri=URI.create(domain);
			realDomain=uri.getAuthority();
			username=null;
		}else if(domain.contains("@")){
			String[] parts=domain.split("@", 2);
			realDomain=parts[1];
			username=parts[0];
		}else{
			realDomain=domain;
			username=null;
		}
		if(realDomain.equalsIgnoreCase(Config.domain)){
			UriBuilder builder=UriBuilder.local().path("account", "login");
			try{
				if(Config.isLocal(new URI(contentURL))){
					builder.queryParam("to", contentURL);
				}
			}catch(URISyntaxException x){
				throw new BadRequestException(x);
			}
			if(username!=null)
				builder.queryParam("username", username);
			return ajaxAwareRedirect(req, resp, builder.build().toString());
		}
		try{
			String uriTemplate=ActivityPub.resolveRemoteInteractionUriTemplate(username, realDomain);
			Object res=ajaxAwareRedirect(req, resp, uriTemplate.replace("{uri}", URLEncoder.encode(contentURL, StandardCharsets.UTF_8)));
			if(res instanceof WebDeltaResponse wdr){
				wdr.runScript("saveRemoteInteractionDomain();");
			}
			return res;
		}catch(ObjectNotFoundException x){
			LOG.trace("Failed to resolve remote interaction URL for {}", req.queryParams("domain"), x);
			if(isAjax(req)){
				return new WebDeltaResponse(resp)
						.show("remoteInteractionErrorMessage")
						.setContent("remoteInteractionErrorMessage", lang(req).get("remote_interaction_bad_domain"));
			}
			throw new UserErrorException("remote_interaction_bad_domain");
		}
	}

	public static Object mentionCompletions(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "q");
		String query=req.queryParams("q");
		List<Pattern> normalizedQueryParts=Arrays.stream(query.toLowerCase(Locale.US).replaceAll("[()\\[\\]*+~<>\\\"@-]", " ").split("\\s+"))
				.filter(Predicate.not(String::isBlank))
				.map(s->Pattern.compile("\\b"+Pattern.quote(s), Pattern.CASE_INSENSITIVE))
				.toList();
		List<User> results=ctx.getSearchController().searchUsers(query, self.user, 10);
		HashMap<Integer, String> highlightedNames=new HashMap<>(), highlightedUsernames=new HashMap<>();
		List<CharacterRange> nameRanges=new ArrayList<>(), usernameRanges=new ArrayList<>();
		for(User u:results){
			nameRanges.clear();
			usernameRanges.clear();
			String name=TextProcessor.escapeHTML(u.getFullName());
			String username=TextProcessor.escapeHTML(u.getFullUsername());
			for(Pattern ptn:normalizedQueryParts){
				Matcher m=ptn.matcher(name);
				matcherLoop:
				while(m.find()){
					CharacterRange range=new CharacterRange(m.start(), m.end());
					for(CharacterRange existing:nameRanges){
						if(existing.intersects(range))
							continue matcherLoop;
					}
					nameRanges.add(range);
				}
				m=ptn.matcher(username);
				matcherLoop:
				while(m.find()){
					CharacterRange range=new CharacterRange(m.start(), m.end());
					for(CharacterRange existing:usernameRanges){
						if(existing.intersects(range))
							continue matcherLoop;
					}
					usernameRanges.add(range);
				}
			}
			nameRanges.sort(null);
			Collections.reverse(nameRanges);
			for(CharacterRange r:nameRanges){
				name=name.substring(0, r.start())+"<b>"+name.substring(r.start(), r.end())+"</b>"+name.substring(r.end());
			}
			usernameRanges.sort(null);
			Collections.reverse(usernameRanges);
			for(CharacterRange r:usernameRanges){
				username=username.substring(0, r.start())+"<b>"+username.substring(r.start(), r.end())+"</b>"+username.substring(r.end());
			}
			highlightedNames.put(u.id, name);
			highlightedUsernames.put(u.id, username);
		}
		return new RenderedTemplateResponse("mention_completions", req)
				.with("users", results)
				.with("highlightedNames", highlightedNames)
				.with("highlightedUsernames", highlightedUsernames);
	}

	public static Object simpleUserCompletions(Request req, Response resp, Account self, ApplicationContext ctx){
		requireQueryParams(req, "q");
		String query=req.queryParams("q");
		List<Pattern> normalizedQueryParts=Arrays.stream(query.toLowerCase(Locale.US).replaceAll("[()\\[\\]*+~<>\\\"@-]", " ").split("\\s+"))
				.filter(Predicate.not(String::isBlank))
				.map(s->Pattern.compile("\\b"+Pattern.quote(s), Pattern.CASE_INSENSITIVE))
				.toList();

		List<User> results=ctx.getSearchController().searchUsers(query, self.user, 10);
		List<CharacterRange> nameRanges=new ArrayList<>();
		JsonArrayBuilder arr=new JsonArrayBuilder();
		for(User u:results){
			nameRanges.clear();
			String name=TextProcessor.escapeHTML(u.getFullName());
			for(Pattern ptn:normalizedQueryParts){
				Matcher m=ptn.matcher(name);
				matcherLoop:
				while(m.find()){
					CharacterRange range=new CharacterRange(m.start(), m.end());
					for(CharacterRange existing:nameRanges){
						if(existing.intersects(range))
							continue matcherLoop;
					}
					nameRanges.add(range);
				}
			}
			nameRanges.sort(null);
			Collections.reverse(nameRanges);
			for(CharacterRange r:nameRanges){
				name=name.substring(0, r.start())+"<b>"+name.substring(r.start(), r.end())+"</b>"+name.substring(r.end());
			}
			arr.add(new JsonObjectBuilder().add("id", u.id).add("title", name));
		}
		return arr.build();
	}

	public static Object privacyPolicy(Request req, Response resp){
		Lang l=lang(req);
		return new RenderedTemplateResponse("privacy_policy", req)
				.with("privacyPolicy", privacyPolicyHTML)
				.pageTitle(l.get("privacy_policy"))
				.addNavBarItem(l.get("footer_about_server"), "/system/about")
				.addNavBarItem(l.get("privacy_policy"));
	}

	public static Object languageChooser(Request req, Response resp){
		if(!isAjax(req))
			throw new BadRequestException();
		RenderedTemplateResponse model=new RenderedTemplateResponse("language_chooser_box", req)
				.with("currentLang", lang(req))
				.with("languages", Lang.list);
		return new WebDeltaResponse(resp).box(lang(req).get("choose_language_title"), model.renderToString(), null, true, "<span class=\"inlineLoader\" style=\"display: none\" id=\"langChooserLoader\"></span>");
	}
}

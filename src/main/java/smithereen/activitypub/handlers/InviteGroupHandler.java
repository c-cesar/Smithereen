package smithereen.activitypub.handlers;

import java.sql.SQLException;
import java.util.Objects;

import smithereen.Utils;
import smithereen.activitypub.ActivityHandlerContext;
import smithereen.activitypub.ActivityTypeHandler;
import smithereen.activitypub.objects.activities.Invite;
import smithereen.model.ForeignGroup;
import smithereen.model.ForeignUser;
import smithereen.model.Group;
import smithereen.model.User;
import smithereen.model.notifications.UserNotifications;
import smithereen.model.UserPrivacySettingKey;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.model.notifications.RealtimeNotification;
import smithereen.storage.GroupStorage;
import smithereen.storage.NotificationsStorage;

public class InviteGroupHandler extends ActivityTypeHandler<ForeignUser, Invite, Group>{
	@Override
	public void handle(ActivityHandlerContext context, ForeignUser actor, Invite invite, Group object) throws SQLException{
		Utils.ensureUserNotBlocked(actor, object);
		if(!(object instanceof ForeignGroup)){
			Group.MembershipState inviterState=context.appContext.getGroupsController().getUserMembershipState(object, actor);
			if(inviterState!=Group.MembershipState.MEMBER && inviterState!=Group.MembershipState.TENTATIVE_MEMBER)
				throw new BadRequestException("Inviter must be a member of this group");
		}
		if(invite.to==null || invite.to.size()!=1 || invite.to.getFirst().link==null)
			throw new BadRequestException("Invite.to must have exactly 1 element and it must be a user ID");
		User user=context.appContext.getObjectLinkResolver().resolve(invite.to.getFirst().link, User.class, true, true, false);
		Utils.ensureUserNotBlocked(actor, user);
		context.appContext.getPrivacyController().enforceUserPrivacy(actor, user, UserPrivacySettingKey.GROUP_INVITE);
		if(object.id==0)
			context.appContext.getObjectLinkResolver().storeOrUpdateRemoteObject(object, object);
		context.appContext.getGroupsController().runLocked(()->{
			Group.MembershipState state=context.appContext.getGroupsController().getUserMembershipState(object, user);
			if(state!=Group.MembershipState.NONE)
				throw new BadRequestException("Can only invite users who aren't members of this group and don't have a pending invitation to it");
			try{
				GroupStorage.putInvitation(object.id, actor.id, user.id, object.isEvent(), Objects.toString(invite.activityPubID, null));
			}catch(SQLException x){
				throw new InternalServerErrorException(x);
			}
		});

		if(!(user instanceof ForeignUser)){
			UserNotifications notifications=NotificationsStorage.getNotificationsFromCache(user.id);
			if(notifications!=null){
				if(object.isEvent())
					notifications.incNewEventInvitationsCount(1);
				else
					notifications.incNewGroupInvitationsCount(1);
			}
			context.appContext.getNotificationsController().sendRealtimeNotifications(user, "groupInvite"+object.id+"_"+actor.id, object.isEvent() ? RealtimeNotification.Type.EVENT_INVITE : RealtimeNotification.Type.GROUP_INVITE, object, null, actor);
		}
	}
}

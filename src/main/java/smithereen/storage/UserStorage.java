package smithereen.storage;

import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import smithereen.Config;
import smithereen.LruCache;
import smithereen.Utils;
import smithereen.activitypub.SerializerContext;
import smithereen.activitypub.objects.Actor;
import smithereen.activitypub.objects.LocalImage;
import smithereen.controllers.FriendsController;
import smithereen.model.Account;
import smithereen.model.BirthdayReminder;
import smithereen.model.ForeignUser;
import smithereen.model.FriendRequest;
import smithereen.model.FriendshipStatus;
import smithereen.model.PrivacySetting;
import smithereen.model.SignupInvitation;
import smithereen.model.PaginatedList;
import smithereen.model.User;
import smithereen.model.UserBanStatus;
import smithereen.model.UserNotifications;
import smithereen.model.UserPresence;
import smithereen.model.UserPrivacySettingKey;
import smithereen.model.UserRole;
import smithereen.model.admin.UserActionLogAction;
import smithereen.model.friends.FollowRelationship;
import smithereen.model.friends.FriendList;
import smithereen.model.media.MediaFileRecord;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.storage.sql.SQLQueryBuilder;
import smithereen.storage.utils.Pair;
import smithereen.text.TextProcessor;
import smithereen.util.NamedMutexCollection;
import spark.utils.StringUtils;

public class UserStorage{
	private static final Logger LOG=LoggerFactory.getLogger(UserStorage.class);

	private static final LruCache<Integer, User> cache=new LruCache<>(500);
	private static final LruCache<String, Integer> cacheByUsername=new LruCache<>(500);
	private static final LruCache<URI, Integer> cacheByActivityPubID=new LruCache<>(500);

	private static final LruCache<Integer, Account> accountCache=new LruCache<>(500);
	private static final LruCache<Integer, BirthdayReminder> birthdayReminderCache=new LruCache<>(500);
	private static final NamedMutexCollection foreignUserUpdateLocks=new NamedMutexCollection();

	public static User getById(int id) throws SQLException{
		return getById(id, false);
	}

	public static User getById(int id, boolean wantDeleted) throws SQLException{
		User user=cache.get(id);
		if(user!=null)
			return user;
		user=new SQLQueryBuilder()
				.selectFrom("users")
				.where("id=?", id)
				.executeAndGetSingleObject(User::fromResultSet);
		if(user!=null){
			if(user.icon!=null && !user.icon.isEmpty() && user.icon.getFirst() instanceof LocalImage li){
				MediaFileRecord mfr=MediaStorage.getMediaFileRecord(li.fileID);
				if(mfr!=null)
					li.fillIn(mfr);
			}
			putIntoCache(user);
		}else if(wantDeleted){
			user=new SQLQueryBuilder()
					.selectFrom("deleted_user_bans")
					.where("user_id=?", id)
					.executeAndGetSingleObject(User::fromDeletedBannedResultSet);
		}
		return user;
	}

	public static List<User> getByIdAsList(List<Integer> ids) throws SQLException{
		if(ids.isEmpty())
			return Collections.emptyList();
		if(ids.size()==1)
			return Collections.singletonList(getById(ids.get(0)));
		Map<Integer, User> users=getById(ids, false);
		return ids.stream().map(users::get).filter(Objects::nonNull).collect(Collectors.toList());
	}

	public static Map<Integer, User> getById(Collection<Integer> _ids, boolean wantDeleted) throws SQLException{
		if(_ids.isEmpty())
			return Map.of();
		if(_ids.size()==1){
			for(int id:_ids){
				User user=getById(id);
				return user==null ? Map.of() : Map.of(id, user);
			}
		}
		Set<Integer> ids=new HashSet<>(_ids);
		Map<Integer, User> result=new HashMap<>(ids.size());
		Iterator<Integer> itr=ids.iterator();
		while(itr.hasNext()){
			Integer id=itr.next();
			User user=cache.get(id);
			if(user!=null){
				itr.remove();
				result.put(id, user);
			}
		}
		if(ids.isEmpty()){
			return result;
		}
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			new SQLQueryBuilder(conn)
					.selectFrom("users")
					.allColumns()
					.whereIn("id", ids)
					.executeAsStream(User::fromResultSet)
					.forEach(u->result.put(u.id, u));
			Set<Long> needAvatars=result.values().stream()
					.map(u->u.icon!=null && !u.icon.isEmpty() && u.icon.getFirst() instanceof LocalImage li ? li : null)
					.filter(li->li!=null && li.fileRecord==null)
					.map(li->li.fileID)
					.collect(Collectors.toSet());
			if(!needAvatars.isEmpty()){
				Map<Long, MediaFileRecord> records=MediaStorage.getMediaFileRecords(needAvatars);
				for(User user:result.values()){
					if(user.icon!=null && !user.icon.isEmpty() && user.icon.getFirst() instanceof LocalImage li && li.fileRecord==null){
						MediaFileRecord mfr=records.get(li.fileID);
						if(mfr!=null)
							li.fillIn(mfr);
					}
				}
			}
			for(int id:ids){
				User u=result.get(id);
				if(u!=null)
					putIntoCache(u);
			}
			if(wantDeleted && ids.size()>result.size()){
				ids.removeIf(result::containsKey);
				new SQLQueryBuilder(conn)
						.selectFrom("deleted_user_bans")
						.whereIn("user_id", ids)
						.executeAsStream(User::fromDeletedBannedResultSet)
						.forEach(u->result.put(u.id, u));
			}
			return result;
		}
	}

	public static User getByUsername(@NotNull String username) throws SQLException{
		username=username.toLowerCase();
		Integer id=cacheByUsername.get(username);
		if(id!=null)
			return getById(id);
		String realUsername;
		String domain="";
		if(username.contains("@")){
			String[] parts=username.split("@");
			realUsername=parts[0];
			domain=parts[1];
		}else{
			realUsername=username;
		}
		User user=new SQLQueryBuilder()
				.selectFrom("users")
				.allColumns()
				.where("username=? AND domain=?", realUsername, domain)
				.executeAndGetSingleObject(User::fromResultSet);
		if(user!=null){
			if(user.icon!=null && !user.icon.isEmpty() && user.icon.getFirst() instanceof LocalImage li){
				MediaFileRecord mfr=MediaStorage.getMediaFileRecord(li.fileID);
				if(mfr!=null)
					li.fillIn(mfr);
			}
			putIntoCache(user);
		}
		return user;
	}

	public static int getIdByUsername(@NotNull String username) throws SQLException{
		String realUsername;
		String domain="";
		if(username.contains("@")){
			String[] parts=username.split("@");
			realUsername=parts[0];
			domain=parts[1];
		}else{
			realUsername=username;
		}
		if(realUsername.length()>Actor.USERNAME_MAX_LENGTH)
			realUsername=realUsername.substring(0, Actor.USERNAME_MAX_LENGTH);
		return new SQLQueryBuilder()
				.selectFrom("users")
				.columns("id")
				.where("username=? AND domain=?", realUsername, domain)
				.executeAndGetInt();
	}

	public static FriendshipStatus getFriendshipStatus(int selfUserID, int targetUserID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=conn.prepareStatement("SELECT `follower_id`,`followee_id`,`mutual`,`accepted` FROM `followings` WHERE (`follower_id`=? AND `followee_id`=?) OR (`follower_id`=? AND `followee_id`=?) LIMIT 1");
			stmt.setInt(1, selfUserID);
			stmt.setInt(2, targetUserID);
			stmt.setInt(3, targetUserID);
			stmt.setInt(4, selfUserID);
			FriendshipStatus status;
			try(ResultSet res=stmt.executeQuery()){
				if(res.next()){
					boolean mutual=res.getBoolean(3);
					boolean accepted=res.getBoolean(4);
					if(mutual)
						return FriendshipStatus.FRIENDS;
					int follower=res.getInt(1);
					int followee=res.getInt(2);
					if(follower==selfUserID && followee==targetUserID)
						status=accepted ? FriendshipStatus.FOLLOWING : FriendshipStatus.FOLLOW_REQUESTED;
					else
						status=FriendshipStatus.FOLLOWED_BY;
				}else{
					return FriendshipStatus.NONE;
				}
			}

			stmt=conn.prepareStatement("SELECT count(*) FROM `friend_requests` WHERE `from_user_id`=? AND `to_user_id`=?");
			if(status==FriendshipStatus.FOLLOWING){
				stmt.setInt(1, selfUserID);
				stmt.setInt(2, targetUserID);
			}else{
				stmt.setInt(2, selfUserID);
				stmt.setInt(1, targetUserID);
			}
			try(ResultSet res=stmt.executeQuery()){
				res.next();
				int count=res.getInt(1);
				if(count==1){
					if(status==FriendshipStatus.FOLLOWING)
						return FriendshipStatus.REQUEST_SENT;
					else
						return FriendshipStatus.REQUEST_RECVD;
				}
			}
			return status;
		}
	}

	public static FriendshipStatus getSimpleFriendshipStatus(int selfUserID, int targetUserID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection();
			ResultSet res=new SQLQueryBuilder(conn)
				.selectFrom("followings")
				.columns("follower_id", "followee_id", "mutual", "accepted")
				.where("(follower_id=? AND followee_id=?) OR (follower_id=? AND followee_id=?)", selfUserID, targetUserID, targetUserID, selfUserID)
				.limit(1, 0)
				.execute()){
			if(res.next()){
				boolean mutual=res.getBoolean(3);
				boolean accepted=res.getBoolean(4);
				if(mutual)
					return FriendshipStatus.FRIENDS;
				int follower=res.getInt(1);
				int followee=res.getInt(2);
				if(follower==selfUserID && followee==targetUserID)
					return accepted ? FriendshipStatus.FOLLOWING : FriendshipStatus.FOLLOW_REQUESTED;
				else
					return FriendshipStatus.FOLLOWED_BY;
			}else{
				return FriendshipStatus.NONE;
			}
		}
	}

	public static Set<Integer> intersectWithFriendIDs(int selfUserID, Collection<Integer> userIDs) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.columns("followee_id")
				.whereIn("followee_id", userIDs)
				.andWhere("mutual=1")
				.andWhere("follower_id=?", selfUserID)
				.executeAndGetIntStream()
				.boxed()
				.collect(Collectors.toSet());
	}

	public static void putFriendRequest(int selfUserID, int targetUserID, String message, boolean followAccepted) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			DatabaseUtils.doWithTransaction(conn, ()->{
				new SQLQueryBuilder(conn)
						.insertInto("friend_requests")
						.value("from_user_id", selfUserID)
						.value("to_user_id", targetUserID)
						.value("message", message)
						.executeNoResult();
				int following=new SQLQueryBuilder(conn)
						.selectFrom("followings")
						.count()
						.where("follower_id=? AND followee_id=?", selfUserID, targetUserID)
						.executeAndGetInt();
				if(following==0){
					new SQLQueryBuilder(conn)
							.insertInto("followings")
							.value("follower_id", selfUserID)
							.value("followee_id", targetUserID)
							.value("accepted", followAccepted)
							.executeNoResult();
					new SQLQueryBuilder(conn)
							.update("users")
							.valueExpr("num_followers", "num_followers+1")
							.where("id=?", targetUserID)
							.executeNoResult();
					cache.remove(targetUserID);
				}
				UserNotifications res=NotificationsStorage.getNotificationsFromCache(targetUserID);
				if(res!=null)
					res.incNewFriendRequestCount(1);
			});
		}
	}

	public static PaginatedList<User> getFriendListForUser(int userID, int offset, int count, boolean onlineOnly, FriendsController.SortOrder order, int listID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			SQLQueryBuilder b=new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.count();
			if(onlineOnly){
				b.join("RIGHT JOIN users ON followings.followee_id=users.id").where("users.is_online=1");
			}
			if(listID>0){
				b.andWhere("(lists & ?)<>0", 1L << (listID-1));
			}
			int total=b.andWhere("follower_id=? AND mutual=1", userID).executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			b=new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.columns("followee_id");
			if(onlineOnly){
				b.join("RIGHT JOIN users ON followings.followee_id=users.id").where("users.is_online=1");
			}
			if(listID>0){
				b.andWhere("(lists & ?)<>0", 1L << (listID-1));
			}

			List<Integer> ids=b.andWhere("follower_id=? AND mutual=1", userID)
					.orderBy(switch(order){
						case HINTS -> "hints_rank DESC, followee_id ASC";
						case RECENTLY_ADDED -> "added_at DESC, followee_id DESC";
						default -> "followee_id ASC";
					})
					.limit(count, offset)
					.executeAndGetIntList();
			return new PaginatedList<>(getByIdAsList(ids), total, offset, count);
		}
	}

	public static List<Integer> getFriendIDsForUser(int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.columns("followee_id")
				.where("follower_id=? AND mutual=1", userID)
				.executeAndGetIntList();
	}

	public static List<URI> getActivityPubFriendList(int userID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=conn.prepareStatement("SELECT followee_id, ap_id FROM followings JOIN `users` ON followee_id=users.id WHERE follower_id=? AND mutual=1 ORDER BY followee_id ASC LIMIT ? OFFSET ?");
			stmt.setInt(1, userID);
			stmt.setInt(2, count);
			stmt.setInt(3, offset);
			try(ResultSet res=stmt.executeQuery()){
				ArrayList<URI> ids=new ArrayList<>();
				while(res.next()){
					String apID=res.getString(2);
					ids.add(apID==null ? Config.localURI("/users/"+res.getInt(1)) : URI.create(apID));
				}
				return ids;
			}
		}
	}

	public static int getUserFriendsCount(int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.count()
				.where("follower_id=? AND mutual=1", userID)
				.executeAndGetInt();
	}

	public static int getUserFollowerOrFollowingCount(int userID, boolean followers) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.count()
				.where((followers ? "followee_id" : "follower_id")+"=? AND accepted=1 AND mutual=0", userID)
				.executeAndGetInt();
	}

	public static PaginatedList<User> getRandomFriendsForProfile(int userID, int count, boolean onlineOnly) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			SQLQueryBuilder b=new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.count();
			if(onlineOnly){
				b.join("RIGHT JOIN users ON followings.followee_id=users.id").where("users.is_online=1");
			}
			int total=b.andWhere("follower_id=? AND mutual=1", userID).executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);

			b=new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.columns("followee_id");
			if(onlineOnly){
				b.join("RIGHT JOIN users ON followings.followee_id=users.id").where("users.is_online=1");
			}
			List<Integer> ids=b.andWhere("follower_id=? AND mutual=1", userID)
					.orderBy("RAND()")
					.limit(count, 0)
					.executeAndGetIntList();
			return new PaginatedList<>(getByIdAsList(ids), total, 0, count);
		}
	}

	public static int getMutualFriendsCount(int userID, int otherUserID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=conn.prepareStatement("SELECT COUNT(*) FROM followings AS friends1 INNER JOIN followings AS friends2 ON friends1.followee_id=friends2.followee_id WHERE friends1.follower_id=? AND friends2.follower_id=? AND friends1.mutual=1 AND friends2.mutual=1");
			stmt.setInt(1, userID);
			stmt.setInt(2, otherUserID);
			return DatabaseUtils.oneFieldToInt(stmt.executeQuery());
		}
	}

	public static PaginatedList<User> getRandomMutualFriendsForProfile(int userID, int otherUserID, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=DatabaseUtils.oneFieldToInt(SQLQueryBuilder.prepareStatement(conn, "SELECT COUNT(*) FROM followings AS friends1 INNER JOIN followings AS friends2 ON friends1.followee_id=friends2.followee_id WHERE friends1.follower_id=? AND friends2.follower_id=? AND friends1.mutual=1 AND friends2.mutual=1", userID, otherUserID).executeQuery());
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT friends1.followee_id FROM followings AS friends1 INNER JOIN followings AS friends2 ON friends1.followee_id=friends2.followee_id WHERE friends1.follower_id=? AND friends2.follower_id=? AND friends1.mutual=1 AND friends2.mutual=1 ORDER BY RAND() LIMIT ?", userID, otherUserID, count);
			try(ResultSet res=stmt.executeQuery()){
				return new PaginatedList<>(getByIdAsList(DatabaseUtils.intResultSetToList(res)), total, 0, count);
			}
		}
	}

	public static List<Integer> getMutualFriendIDsForUser(int userID, int otherUserID, int offset, int count, boolean useHints) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String orderBy=useHints ? "friends1.hints_rank DESC, friends1.followee_id ASC" : "friends1.followee_id ASC";
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT friends1.followee_id FROM followings AS friends1 INNER JOIN followings AS friends2 ON friends1.followee_id=friends2.followee_id " +
					"WHERE friends1.follower_id=? AND friends2.follower_id=? AND friends1.mutual=1 AND friends2.mutual=1 ORDER BY "+orderBy+" LIMIT ? OFFSET ?", userID, otherUserID, count, offset);
			return DatabaseUtils.intResultSetToList(stmt.executeQuery());
		}
	}

	public static PaginatedList<User> getMutualFriendListForUser(int userID, int otherUserID, int offset, int count, boolean useHints) throws SQLException{
		return new PaginatedList<>(getByIdAsList(getMutualFriendIDsForUser(userID, otherUserID, offset, count, useHints)), getMutualFriendsCount(userID, otherUserID), offset, count);
	}

	public static PaginatedList<User> getNonMutualFollowers(int userID, boolean followers, boolean accepted, int offset, int count, boolean orderByFollowers) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String fld1=followers ? "follower_id" : "followee_id";
			String fld2=followers ? "followee_id" : "follower_id";
			int total=new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.count()
					.where(fld2+"=? AND accepted=? AND mutual=0", userID, accepted)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			SQLQueryBuilder b=new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.columns(fld1)
					.where(fld2+"=? AND accepted=? AND mutual=0", userID, accepted);
			if(orderByFollowers){
				b.join("RIGHT JOIN `users` ON "+fld1+"=`users`.id")
						.orderBy("num_followers DESC");
			}else{
				b.orderBy(fld1+" ASC");
			}
			List<Integer> ids=b.limit(count, offset)
					.executeAndGetIntList();
			return new PaginatedList<>(getByIdAsList(ids), total, offset, count);
		}
	}

	public static PaginatedList<FriendRequest> getIncomingFriendRequestsForUser(int userID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("friend_requests")
					.count()
					.where("to_user_id=?", userID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			// 1. collect the IDs of mutual friends for each friend request
			Map<Integer, List<Integer>> mutualFriendIDs=new HashMap<>();
			List<FriendRequest> reqs=new SQLQueryBuilder(conn)
					.selectFrom("friend_requests")
					.columns("message", "from_user_id")
					.where("to_user_id=?", userID)
					.orderBy("id DESC")
					.limit(count, offset)
					.executeAsStream(FriendRequest::fromResultSet)
					.peek(req->{
						try{
							req.mutualFriendsCount=getMutualFriendsCount(userID, req.from.id);
							if(req.mutualFriendsCount>0){
								mutualFriendIDs.put(req.from.id, getMutualFriendIDsForUser(userID, req.from.id, 0, 4, true));
							}
						}catch(SQLException x){
							LOG.warn("Exception while getting mutual friends for {} and {}", userID, req.from.id, x);
						}
					}).toList();
			if(mutualFriendIDs.isEmpty())
				return new PaginatedList<>(reqs, total, offset, count);
			// 2. make a list of distinct users we need
			Set<Integer> needUsers=mutualFriendIDs.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
			// 3. get them all in one go
			Map<Integer, User> mutualFriends=getById(needUsers, false);
			// 4. finally, put them into friend requests
			for(FriendRequest req: reqs){
				List<Integer> ids=mutualFriendIDs.get(req.from.id);
				if(ids==null)
					continue;
				req.mutualFriends=ids.stream().map(mutualFriends::get).collect(Collectors.toList());
			}
			return new PaginatedList<>(reqs, total, offset, count);
		}
	}

	public static FriendRequest getFriendRequest(int userID, int requesterID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("friend_requests")
				.where("from_user_id=? AND to_user_id=?", requesterID, userID)
				.executeAndGetSingleObject(FriendRequest::fromResultSet);
	}

	public static boolean acceptFriendRequest(int userID, int targetUserID, boolean followAccepted) throws SQLException{
		boolean[] result={true};
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			DatabaseUtils.doWithTransaction(conn, ()->{
				PreparedStatement stmt=conn.prepareStatement("DELETE FROM `friend_requests` WHERE `from_user_id`=? AND `to_user_id`=?");
				stmt.setInt(1, targetUserID);
				stmt.setInt(2, userID);
				if(stmt.executeUpdate()!=1){
					conn.createStatement().execute("ROLLBACK");
					result[0]=false;
					return;
				}
				int hintsRank=new SQLQueryBuilder(conn)
						.selectFrom("followings")
						.selectExpr("MAX(hints_rank)+20")
						.where("follower_id=?", userID)
						.executeAndGetInt();
				new SQLQueryBuilder(conn)
						.insertInto("followings")
						.value("follower_id", userID)
						.value("followee_id", targetUserID)
						.value("mutual", true)
						.value("accepted", followAccepted)
						.value("hints_rank", hintsRank)
						.executeNoResult();
				hintsRank=new SQLQueryBuilder(conn)
						.selectFrom("followings")
						.selectExpr("MAX(hints_rank)+20")
						.where("follower_id=?", targetUserID)
						.executeAndGetInt();
				stmt=SQLQueryBuilder.prepareStatement(conn, "UPDATE `followings` SET `mutual`=1, added_at=CURRENT_TIMESTAMP(), hints_rank=? WHERE `follower_id`=? AND `followee_id`=?", hintsRank, targetUserID, userID);
				if(stmt.executeUpdate()!=1){
					conn.createStatement().execute("ROLLBACK");
					result[0]=false;
					return;
				}
				new SQLQueryBuilder(conn)
						.update("users")
						.valueExpr("num_followers", "num_followers+1")
						.valueExpr("num_friends", "num_friends+1")
						.where("id=?", targetUserID)
						.executeNoResult();
				new SQLQueryBuilder(conn)
						.update("users")
						.valueExpr("num_following", "num_following+1")
						.valueExpr("num_friends", "num_friends+1")
						.where("id=?", userID)
						.executeNoResult();
				cache.remove(targetUserID);
				cache.remove(userID);
				UserNotifications n=NotificationsStorage.getNotificationsFromCache(userID);
				if(n!=null)
					n.incNewFriendRequestCount(-1);
				removeBirthdayReminderFromCache(List.of(userID, targetUserID));
			});
		}
		return result[0];
	}

	public static void deleteFriendRequest(int userID, int targetUserID) throws SQLException{
		int rows=new SQLQueryBuilder()
				.deleteFrom("friend_requests")
				.where("from_user_id=? AND to_user_id=?", targetUserID, userID)
				.executeUpdate();
		UserNotifications n=NotificationsStorage.getNotificationsFromCache(userID);
		if(n!=null)
			n.incNewFriendRequestCount(-rows);
	}

	public static void unfriendUser(int userID, int targetUserID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			DatabaseUtils.doWithTransaction(conn, ()->{
				int numRows=new SQLQueryBuilder(conn)
						.deleteFrom("followings")
						.where("follower_id=? AND followee_id=?", userID, targetUserID)
						.executeUpdate();
				if(numRows==0)
					return;
				int mutual=new SQLQueryBuilder(conn)
						.update("followings")
						.where("follower_id=? AND followee_id=?", targetUserID, userID)
						.value("mutual", 0)
						.executeUpdate();
				SQLQueryBuilder b1=new SQLQueryBuilder(conn)
						.update("users")
						.where("id=?", userID)
						.valueExpr("num_following", "num_following-1");
				SQLQueryBuilder b2=new SQLQueryBuilder(conn)
						.update("users")
						.where("id=?", targetUserID)
						.valueExpr("num_followers", "num_followers-1");
				if(mutual>0){
					b1.valueExpr("num_friends", "num_friends-1");
					b2.valueExpr("num_friends", "num_friends-1");
				}else{
					new SQLQueryBuilder(conn)
							.deleteFrom("friend_requests")
							.where("from_user_id=? AND to_user_id=?", userID, targetUserID)
							.executeNoResult();
				}
				b1.executeNoResult();
				b2.executeNoResult();
				cache.remove(targetUserID);
				cache.remove(userID);
				removeBirthdayReminderFromCache(List.of(userID, targetUserID));
			});
		}
	}

	public static void followUser(int userID, int targetUserID, boolean accepted, boolean ignoreAlreadyFollowing, boolean updateNumbers) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			conn.createStatement().execute("START TRANSACTION");
			try{
				boolean mutual=false;
				PreparedStatement stmt=conn.prepareStatement("SELECT count(*) FROM `followings` WHERE `follower_id`=? AND `followee_id`=?");
				stmt.setInt(1, targetUserID);
				stmt.setInt(2, userID);
				try(ResultSet res=stmt.executeQuery()){
					res.next();
					mutual=res.getInt(1)==1;
				}
				stmt.setInt(1, userID);
				stmt.setInt(2, targetUserID);
				try(ResultSet res=stmt.executeQuery()){
					res.next();
					if(res.getInt(1)==1){
						if(ignoreAlreadyFollowing){
							conn.createStatement().execute("ROLLBACK");
							return;
						}
						throw new SQLException("Already following");
					}
				}

				new SQLQueryBuilder(conn)
						.insertInto("followings")
						.value("follower_id", userID)
						.value("followee_id", targetUserID)
						.value("mutual", mutual)
						.value("accepted", accepted)
						.executeNoResult();

				if(mutual){
					new SQLQueryBuilder(conn)
							.update("followings")
							.value("mutual", true)
							.valueExpr("added_at", "CURRENT_TIMESTAMP()")
							.where("follower_id=? AND followee_id=?", targetUserID, userID)
							.executeNoResult();
					removeBirthdayReminderFromCache(List.of(userID, targetUserID));
				}

				if(updateNumbers){
					SQLQueryBuilder b1=new SQLQueryBuilder(conn)
							.update("users")
							.where("id=?", userID)
							.valueExpr("num_following", "num_following+1");
					SQLQueryBuilder b2=new SQLQueryBuilder(conn)
							.update("users")
							.where("id=?", targetUserID)
							.valueExpr("num_followers", "num_followers+1");
					if(mutual){
						b1.valueExpr("num_friends", "num_friends+1");
						b2.valueExpr("num_friends", "num_friends+1");
					}
					b1.executeNoResult();
					b2.executeNoResult();
				}
				cache.remove(targetUserID);
				cache.remove(userID);

				conn.createStatement().execute("COMMIT");
			}catch(SQLException x){
				conn.createStatement().execute("ROLLBACK");
				throw new SQLException(x);
			}
		}
	}

	public static PaginatedList<SignupInvitation> getInvites(int userID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("signup_invitations")
					.count()
					.where("owner_id=? AND signups_remaining>0", userID)
					.executeAndGetInt();
			List<SignupInvitation> res=new SQLQueryBuilder(conn)
					.selectFrom("signup_invitations")
					.allColumns()
					.where("owner_id=? AND signups_remaining>0", userID)
					.orderBy("`created` DESC")
					.limit(count, offset)
					.executeAsStream(SignupInvitation::fromResultSet)
					.toList();
			return new PaginatedList<>(res, total, offset, count);
		}
	}

	public static int putInvite(int userID, byte[] code, int signups, String email, String extra) throws SQLException{
		return new SQLQueryBuilder()
				.insertInto("signup_invitations")
				.value("owner_id", userID)
				.value("code", code)
				.value("signups_remaining", signups)
				.value("email", email)
				.value("extra", extra)
				.executeAndGetID();
	}

	public static void changeBasicInfo(User user, String firstName, String lastName, String middleName, String maidenName, User.Gender gender, LocalDate bdate) throws SQLException{
		new SQLQueryBuilder()
				.update("users")
				.where("id=?", user.id)
				.value("fname", firstName)
				.value("lname", lastName)
				.value("gender", gender)
				.value("bdate", bdate)
				.value("middle_name", middleName)
				.value("maiden_name", maidenName)
				.executeNoResult();
		removeFromCache(user);
		updateQSearchIndex(getById(user.id));
		removeBirthdayReminderFromCache(getFriendIDsForUser(user.id));
	}

	public static void updateAbout(User user, String about, String aboutSource) throws SQLException{
		new SQLQueryBuilder()
				.update("users")
				.where("id=?", user.id)
				.value("about", about)
				.value("about_source", aboutSource)
				.executeNoResult();
		removeFromCache(user);
	}

	public static void updateExtendedFields(User user, String fieldsJson) throws SQLException{
		new SQLQueryBuilder()
				.update("users")
				.where("id=?", user.id)
				.value("profile_fields", fieldsJson)
				.executeNoResult();
		removeFromCache(user);
	}

	public static void updateUsername(User user, String username) throws SQLException{
		new SQLQueryBuilder()
				.update("users")
				.where("id=?", user.id)
				.value("username", username)
				.executeNoResult();
		removeFromCache(user);
		updateQSearchIndex(getById(user.id));
	}

	public static int getLocalUserCount() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("accounts")
				.count()
				.executeAndGetInt();
	}

	public static int getActiveLocalUserCount(long time) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("accounts")
				.count()
				.where("last_active>?", new Timestamp(System.currentTimeMillis()-time))
				.executeAndGetInt();
	}

	public static void updateProfilePicture(User user, String serializedPic) throws SQLException{
		new SQLQueryBuilder()
				.update("users")
				.value("avatar", serializedPic)
				.where("id=?", user.id)
				.executeNoResult();
		removeFromCache(user);
	}

	public static int putOrUpdateForeignUser(ForeignUser user) throws SQLException{
		if(user.isServiceActor)
			throw new IllegalArgumentException("Can't store a service actor as a user");

		String key=user.activityPubID.toString().toLowerCase();
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			foreignUserUpdateLocks.acquire(key);
			int existingUserID=new SQLQueryBuilder(conn)
					.selectFrom("users")
					.columns("id")
					.where("ap_id=?", Objects.toString(user.activityPubID))
					.executeAndGetInt();
			boolean isNew=existingUserID==-1;
			SQLQueryBuilder bldr=new SQLQueryBuilder(conn);
			if(!isNew){
				bldr.update("users").where("id=?", existingUserID);
			}else{
				bldr.insertInto("users");
			}

			bldr.valueExpr("last_updated", "CURRENT_TIMESTAMP()")
					.value("fname", user.firstName)
					.value("lname", user.lastName)
					.value("bdate", user.birthDate)
					.value("username", user.username)
					.value("domain", user.domain)
					.value("public_key", user.publicKey.getEncoded())
					.value("ap_url", Objects.toString(user.url, null))
					.value("ap_inbox", Objects.toString(user.inbox, null))
					.value("ap_shared_inbox", Objects.toString(user.sharedInbox, null))
					.value("ap_id", user.activityPubID.toString())
					.value("about", user.summary)
					.value("gender", user.gender)
					.value("avatar", user.icon!=null ? user.icon.getFirst().asActivityPubObject(new JsonObject(), new SerializerContext(null, (String)null)).toString() : null)
					.value("profile_fields", user.serializeProfileFields())
					.value("flags", user.flags)
					.value("middle_name", user.middleName)
					.value("maiden_name", user.maidenName)
					.value("endpoints", user.serializeEndpoints())
					.value("privacy", user.privacySettings!=null ? Utils.gson.toJson(user.privacySettings) : null)
					.value("ban_info", user.banInfo!=null ? Utils.gson.toJson(user.banInfo) : null);

			if(isNew){
				bldr.value("num_followers", user.getRawFollowersCount())
						.value("num_following", user.getRawFollowingCount())
						.value("num_friends", user.getFriendsCount());
				existingUserID=bldr.executeAndGetID();
			}else{
				bldr.valueExpr("num_followers", "GREATEST(?, (SELECT COUNT(*) FROM followings WHERE followee_id=?))", user.getRawFollowersCount(), existingUserID)
						.valueExpr("num_following", "GREATEST(?, (SELECT COUNT(*) FROM followings WHERE follower_id=?))", user.getRawFollowingCount(), existingUserID)
						.valueExpr("num_friends", "GREATEST(?, (SELECT COUNT(*) FROM followings WHERE followee_id=? AND mutual=1))", user.getFriendsCount(), existingUserID);
				bldr.executeNoResult();

				ResultSet res=new SQLQueryBuilder(conn)
						.selectFrom("users")
						.columns("num_followers", "num_following", "num_friends")
						.where("id=?", existingUserID)
						.execute();
				try(res){
					res.next();
					user.setFollowersCount(res.getLong("num_followers"));
					user.setFollowingCount(res.getLong("num_following"));
					user.setFriendsCount(res.getLong("num_friends"));
				}
			}
			user.id=existingUserID;
			user.lastUpdated=Instant.now();
			putIntoCache(user);

			if(isNew){
				new SQLQueryBuilder(conn)
						.insertInto("qsearch_index")
						.value("user_id", existingUserID)
						.value("string", getQSearchStringForUser(user))
						.executeNoResult();
			}else{
				updateQSearchIndex(user);
			}

			return existingUserID;
		}catch(SQLIntegrityConstraintViolationException x){
			// Rare case: user with a matching username@domain but a different AP ID already exists
			try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
				int oldID=new SQLQueryBuilder(conn)
						.selectFrom("users")
						.columns("id")
						.where("username=? AND domain=? AND ap_id<>?", user.username, user.domain, user.activityPubID)
						.executeAndGetInt();
				if(oldID<=0){
					LOG.warn("Didn't find an existing user with username {}@{} while trying to deduplicate {}", user.username, user.domain, user.activityPubID);
					throw x;
				}
				LOG.info("Deduplicating user rows: username {}@{}, old local ID {}, new AP ID {}", user.username, user.domain, oldID, user.activityPubID);
				// Assign a temporary random username to this existing user row to get it out of the way
				new SQLQueryBuilder(conn)
						.update("users")
						.value("username", UUID.randomUUID().toString())
						.where("id=?", oldID)
						.executeNoResult();
				// Try again
				return putOrUpdateForeignUser(user);
			}
		}finally{
			foreignUserUpdateLocks.release(key);
		}
	}

	public static User getUserByActivityPubID(URI apID) throws SQLException{
		if(Config.isLocal(apID)){
			String[] components=apID.getPath().substring(1).split("/");
			if(components.length<2)
				return null;
			if(!"users".equals(components[0]))
				return null;
			return getById(Utils.parseIntOrDefault(components[1], 0));
		}
		return getForeignUserByActivityPubID(apID);
	}

	public static ForeignUser getForeignUserByActivityPubID(URI apID) throws SQLException{
		Integer id=cacheByActivityPubID.get(apID);
		if(id!=null)
			return getById(id) instanceof ForeignUser fu ? fu : null;
		ForeignUser user=new SQLQueryBuilder()
				.selectFrom("users")
				.where("ap_id=?", apID)
				.executeAndGetSingleObject(ForeignUser::fromResultSet);
		if(user!=null){
			cacheByActivityPubID.put(apID, user.id);
			cache.put(user.id, user);
			cacheByUsername.put(user.getFullUsername().toLowerCase(), user.id);
		}
		return user;
	}

	public static List<URI> getFollowerInboxes(int userID) throws SQLException{
		return getFollowerInboxes(userID, Set.of());
	}

	public static List<URI> getFollowerInboxes(int userID, Set<Integer> except) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String query="SELECT DISTINCT IFNULL(`ap_shared_inbox`, `ap_inbox`) FROM `users` WHERE `id` IN (SELECT `follower_id` FROM `followings` WHERE `followee_id`=?)";
			if(except!=null && !except.isEmpty()){
				query+=" AND `id` NOT IN ("+except.stream().map(Object::toString).collect(Collectors.joining(", "))+")";
			}
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, query, userID);
			return DatabaseUtils.resultSetToObjectStream(stmt.executeQuery(), r->{
				String url=r.getString(1);
				if(url==null)
					return null;
				return URI.create(url);
			}, null).filter(Objects::nonNull).collect(Collectors.toList());
		}
	}

	public static List<URI> getFriendInboxes(int userID, Set<Integer> except) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String query="SELECT DISTINCT IFNULL(`ap_shared_inbox`, `ap_inbox`) FROM `users` WHERE `id` IN (SELECT `follower_id` FROM `followings` WHERE `followee_id`=? AND mutual=1)";
			if(except!=null && !except.isEmpty()){
				query+=" AND `id` NOT IN ("+except.stream().map(Object::toString).collect(Collectors.joining(", "))+")";
			}
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, query, userID);
			return DatabaseUtils.resultSetToObjectStream(stmt.executeQuery(), r->{
				String url=r.getString(1);
				if(url==null)
					return null;
				return URI.create(url);
			}, null).filter(Objects::nonNull).collect(Collectors.toList());
		}
	}

	public static List<URI> getUserFollowerURIs(int userID, boolean followers, int offset, int count, int[] total) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			String fld1=followers ? "follower_id" : "followee_id";
			String fld2=followers ? "followee_id" : "follower_id";
			if(total!=null){
				total[0]=new SQLQueryBuilder(conn)
						.selectFrom("followings")
						.count()
						.where(fld2+"=?", userID)
						.executeAndGetInt();
			}
			if(count>0){
				PreparedStatement stmt=conn.prepareStatement("SELECT `ap_id`,`id` FROM `followings` INNER JOIN `users` ON `users`.`id`=`"+fld1+"` WHERE `"+fld2+"`=? AND `accepted`=1 LIMIT ? OFFSET ?");
				stmt.setInt(1, userID);
				stmt.setInt(2, count);
				stmt.setInt(3, offset);
				ArrayList<URI> list=new ArrayList<>();
				try(ResultSet res=stmt.executeQuery()){
					while(res.next()){
						String _u=res.getString(1);
						if(_u==null){
							list.add(Config.localURI("/users/"+res.getInt(2)));
						}else{
							list.add(URI.create(_u));
						}
					}
				}
				return list;
			}
			return Collections.emptyList();
		}
	}

	public static List<FollowRelationship> getUserLocalFollowers(int userID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT followings.* FROM followings INNER JOIN `users` on `users`.id=follower_id WHERE followee_id=? AND accepted=1 AND `users`.ap_id IS NULL", userID);
			return DatabaseUtils.resultSetToObjectStream(stmt.executeQuery(), FollowRelationship::fromResultSet, null).toList();
		}
	}

	public static void setFollowAccepted(int followerID, int followeeID, boolean accepted) throws SQLException{
		new SQLQueryBuilder()
				.update("followings")
				.value("accepted", accepted)
				.where("follower_id=? AND followee_id=?", followerID, followeeID)
				.executeNoResult();
	}

	public static Account getAccount(int id) throws SQLException{
		Account acc=accountCache.get(id);
		if(acc!=null)
			return acc;
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=conn.prepareStatement("SELECT * FROM accounts WHERE id=?");
			stmt.setInt(1, id);
			try(ResultSet res=stmt.executeQuery()){
				if(res.next()){
					acc=Account.fromResultSet(res);
					accountCache.put(acc.id, acc);
					return acc;
				}
			}
		}
		return null;
	}

	public static Map<Integer, Account> getAccounts(Collection<Integer> ids) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("accounts")
				.allColumns()
				.whereIn("id", ids)
				.executeAsStream(Account::fromResultSet)
				.collect(Collectors.toMap(a->a.id, Function.identity()));
	}

	public static void setAccountRole(Account account, int role, int promotedBy) throws SQLException{
		new SQLQueryBuilder()
				.update("accounts")
				.value("role", role>0 ? role : null)
				.value("promoted_by", promotedBy>0 ? promotedBy : null)
				.where("id=?", account.id)
				.executeNoResult();
		accountCache.remove(account.id);
		SessionStorage.removeFromUserPermissionsCache(account.user.id);
	}

	public static void resetAccountsCache(){
		accountCache.evictAll();
	}

	public static List<User> getAdmins() throws SQLException{
		Set<Integer> rolesToShow=Config.userRoles.values()
				.stream()
				.filter(r->r.permissions().contains(UserRole.Permission.VISIBLE_IN_STAFF))
				.map(UserRole::id)
				.collect(Collectors.toSet());
		if(rolesToShow.isEmpty())
			return List.of();
		return getByIdAsList(new SQLQueryBuilder()
				.selectFrom("accounts")
				.columns("user_id")
				.whereIn("role", rolesToShow)
				.executeAndGetIntList());
	}

	public static int getPeerDomainCount() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("users")
				.selectExpr("COUNT(DISTINCT domain)")
				.executeAndGetInt()-1; // -1 for local domain (empty string)
	}

	public static List<String> getPeerDomains() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("users")
				.distinct()
				.columns("domain")
				.orderBy("domain asc")
				.executeAsStream(r->r.getString(1))
				.filter(s->s.length()>0)
				.toList();
	}

	public static boolean isUserBlocked(int ownerID, int targetID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("blocks_user_user")
				.count()
				.where("owner_id=? AND user_id=?", ownerID, targetID)
				.executeAndGetInt()==1;
	}

	public static void blockUser(int selfID, int targetID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			new SQLQueryBuilder(conn)
					.insertInto("blocks_user_user")
					.value("owner_id", selfID)
					.value("user_id", targetID)
					.executeNoResult();
			new SQLQueryBuilder(conn)
					.deleteFrom("friend_requests")
					.where("(from_user_id=? AND to_user_id=?) OR (from_user_id=? AND to_user_id=?)", selfID, targetID, targetID, selfID)
					.executeNoResult();
			unfriendUser(selfID, targetID);
			unfriendUser(targetID, selfID);
		}
	}

	public static void unblockUser(int selfID, int targetID) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("blocks_user_user")
				.where("owner_id=? AND user_id=?", selfID, targetID)
				.executeNoResult();
	}

	public static List<User> getBlockedUsers(int selfID) throws SQLException{
		return getByIdAsList(new SQLQueryBuilder()
				.selectFrom("blocks_user_user")
				.columns("user_id")
				.where("owner_id=?", selfID)
				.executeAndGetIntList());
	}

	public static List<User> getBlockingUsers(int selfID) throws SQLException{
		return getByIdAsList(new SQLQueryBuilder()
				.selectFrom("blocks_user_user")
				.columns("owner_id")
				.where("user_id=?", selfID)
				.executeAndGetIntList());
	}

	public static boolean isDomainBlocked(int selfID, String domain) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("blocks_user_domain")
				.count()
				.where("owner_id=? AND domain=?", selfID, domain)
				.executeAndGetInt()==1;
	}

	public static List<String> getBlockedDomains(int selfID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("blocks_user_domain")
				.columns("domain")
				.where("owner_id=?", selfID)
				.executeAsStream(r->r.getString(1))
				.toList();
	}

	public static void blockDomain(int selfID, String domain) throws SQLException{
		new SQLQueryBuilder()
				.insertInto("blocks_user_domain")
				.value("owner_id", selfID)
				.value("domain", domain)
				.executeNoResult();
	}

	public static void unblockDomain(int selfID, String domain) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("blocks_user_domain")
				.where("owner_id=? AND domain=?", selfID, domain)
				.executeNoResult();
	}

	private static void putIntoCache(User user){
		cache.put(user.id, user);
		cacheByUsername.put(user.getFullUsername().toLowerCase(), user.id);
		if(user instanceof ForeignUser)
			cacheByActivityPubID.put(user.activityPubID, user.id);
	}

	private static void removeFromCache(User user){
		cache.remove(user.id);
		cacheByUsername.remove(user.getFullUsername().toLowerCase());
		if(user instanceof ForeignUser)
			cacheByActivityPubID.remove(user.activityPubID);
	}

	static String getQSearchStringForUser(User user){
		StringBuilder sb=new StringBuilder(TextProcessor.transliterate(user.firstName));
		if(user.lastName!=null){
			sb.append(' ');
			sb.append(TextProcessor.transliterate(user.lastName));
		}
		if(user.middleName!=null){
			sb.append(' ');
			sb.append(TextProcessor.transliterate(user.middleName));
		}
		if(user.maidenName!=null){
			sb.append(' ');
			sb.append(TextProcessor.transliterate(user.maidenName));
		}
		sb.append(' ');
		sb.append(user.username);
		if(user.domain!=null){
			sb.append(' ');
			sb.append(user.domain);
		}
		return sb.toString();
	}

	static void updateQSearchIndex(User user) throws SQLException{
		new SQLQueryBuilder()
				.update("qsearch_index")
				.value("string", getQSearchStringForUser(user))
				.where("user_id=?", user.id)
				.executeNoResult();
	}

	public static void removeBirthdayReminderFromCache(List<Integer> userIDs){
		for(Integer id:userIDs){
			birthdayReminderCache.remove(id);
		}
	}

	public static void getFriendIdsWithBirthdaysTodayAndTomorrow(int userID, LocalDate date, List<Integer> today, List<Integer> tomorrow) throws SQLException{
		LocalDate nextDay=date.plusDays(1);
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT `users`.id, `users`.bdate FROM `users` RIGHT JOIN followings ON followings.followee_id=`users`.id"+
							" WHERE followings.follower_id=? AND followings.mutual=1 AND `users`.bdate IS NOT NULL"+
							" AND ((DAY(`users`.bdate)=? AND MONTH(`users`.bdate)=?) OR (DAY(`users`.bdate)=? AND MONTH(`users`.bdate)=?))",
					userID, date.getDayOfMonth(), date.getMonthValue(), nextDay.getDayOfMonth(), nextDay.getMonthValue());

			try(ResultSet res=stmt.executeQuery()){
				while(res.next()){
					int id=res.getInt(1);
					LocalDate bdate=DatabaseUtils.getLocalDate(res, 2);
					Objects.requireNonNull(bdate);
					if(bdate.getDayOfMonth()==date.getDayOfMonth()){
						today.add(id);
					}else{
						tomorrow.add(id);
					}
				}
			}
		}
	}

	public static BirthdayReminder getBirthdayReminderForUser(int userID, LocalDate date) throws SQLException{
		BirthdayReminder r=birthdayReminderCache.get(userID);
		if(r!=null && r.forDay.equals(date))
			return r;
		LocalDate nextDay=date.plusDays(1);
		ArrayList<Integer> today=new ArrayList<>(), tomorrow=new ArrayList<>();
		getFriendIdsWithBirthdaysTodayAndTomorrow(userID, date, today, tomorrow);
		r=new BirthdayReminder();
		r.forDay=date;
		if(!today.isEmpty()){
			r.day=date;
			r.userIDs=today;
		}else if(!tomorrow.isEmpty()){
			r.day=nextDay;
			r.userIDs=tomorrow;
		}else{
			r.userIDs=Collections.emptyList();
		}
		birthdayReminderCache.put(userID, r);
		return r;
	}

	public static List<Integer> getFriendsWithBirthdaysInMonth(int userID, int month) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT `users`.id FROM `users` RIGHT JOIN followings ON followings.followee_id=`users`.id"+
					" WHERE followings.follower_id=? AND followings.mutual=1 AND `users`.bdate IS NOT NULL AND MONTH(`users`.bdate)=?", userID, month);
			return DatabaseUtils.intResultSetToList(stmt.executeQuery());
		}
	}

	public static List<Integer> getFriendsWithBirthdaysOnDay(int userID, int month, int day) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT `users`.id FROM `users` RIGHT JOIN followings ON followings.followee_id=`users`.id"+
					" WHERE followings.follower_id=? AND followings.mutual=1 AND `users`.bdate IS NOT NULL AND MONTH(`users`.bdate)=? AND DAY(`users`.bdate)=?", userID, month, day);
			return DatabaseUtils.intResultSetToList(stmt.executeQuery());
		}
	}

	public static Map<URI, Integer> getFriendsByActivityPubIDs(Collection<URI> ids, int userID) throws SQLException{
		if(ids.isEmpty())
			return Map.of();
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			ArrayList<Integer> localIDs=new ArrayList<>();
			ArrayList<String> remoteIDs=new ArrayList<>();
			for(URI id: ids){
				if(Config.isLocal(id)){
					String path=id.getPath();
					if(StringUtils.isEmpty(path))
						continue;
					String[] pathSegments=path.split("/");
					if(pathSegments.length!=3 || !"users".equals(pathSegments[1])) // "", "users", id
						continue;
					int uid=Utils.safeParseInt(pathSegments[2]);
					if(uid>0)
						localIDs.add(uid);
				}else{
					remoteIDs.add(id.toString());
				}
			}
			HashMap<Integer, URI> localIdToApIdMap=new HashMap<>();
			if(!remoteIDs.isEmpty()){
				try(ResultSet res=new SQLQueryBuilder(conn).selectFrom("users").columns("id", "ap_id").whereIn("ap_id", remoteIDs).execute()){
					while(res.next()){
						int localID=res.getInt(1);
						localIDs.add(localID);
						localIdToApIdMap.put(localID, URI.create(res.getString(2)));
					}
				}
			}
			if(localIDs.isEmpty())
				return Map.of();
			return new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.columns("followee_id")
					.whereIn("followee_id", localIDs)
					.andWhere("mutual=1 AND accepted=1 AND follower_id=?", userID)
					.executeAsStream(res->res.getInt(1))
					.collect(Collectors.toMap(id->localIdToApIdMap.computeIfAbsent(id, UserStorage::localUserURI), Function.identity()));
		}
	}

	private static URI localUserURI(int id){
		return Config.localURI("/users/"+id);
	}

	public static int getLocalFollowersCount(int userID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT COUNT(*) FROM `followings` JOIN `users` ON `follower_id`=`users`.id WHERE followee_id=? AND accepted=1 AND `users`.domain=''", userID);
			return DatabaseUtils.oneFieldToInt(stmt.executeQuery());
		}
	}

	public static void setPrivacySettings(User user, Map<UserPrivacySettingKey, PrivacySetting> settings) throws SQLException{
		new SQLQueryBuilder()
				.update("users")
				.value("privacy", Utils.gson.toJson(settings))
				.where("id=?", user.id)
				.executeNoResult();
		removeFromCache(user);
	}

	public static Set<Integer> getFriendIDsWithDomain(int userID, String domain) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT id FROM followings JOIN `users` ON `followee_id`=`users`.id WHERE follower_id=? AND mutual=1 AND `users`.domain=?", userID, domain);
			return DatabaseUtils.resultSetToObjectStream(stmt.executeQuery(), rs->rs.getInt(1), null).collect(Collectors.toSet());
		}
	}

	public static int getCountOfFriendsOfFriendsWithDomain(int userID, String domain) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn,
					"SELECT COUNT(*) FROM followings JOIN `users` ON followings.follower_id=`users`.id WHERE followee_id IN (SELECT follower_id FROM followings WHERE followee_id=? AND mutual=1) AND mutual=1 AND `users`.domain=?",
					userID, domain);
			return DatabaseUtils.oneFieldToInt(stmt.executeQuery());
		}
	}

	public static void deleteForeignUser(ForeignUser user) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			new SQLQueryBuilder(conn)
					.deleteFrom("users")
					.where("id=?", user.id)
					.executeNoResult();
			if(user.banInfo!=null){
				new SQLQueryBuilder(conn)
						.insertInto("deleted_user_bans")
						.value("user_id", user.id)
						.value("domain", user.domain)
						.value("ban_status", user.banStatus)
						.value("ban_info", Utils.gson.toJson(user.banInfo))
						.executeNoResult();
			}
			removeFromCache(user);
		}
	}

	public static void deleteAccount(Account account) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			// Delete media file refs first because triggers don't trigger on cascade deletes. Argh.
			new SQLQueryBuilder(conn)
					.deleteFrom("media_file_refs")
					.where("owner_user_id=?", account.user.id)
					.executeNoResult();

			new SQLQueryBuilder(conn)
					.deleteFrom("accounts")
					.where("id=?", account.id)
					.executeNoResult();
			new SQLQueryBuilder(conn)
					.deleteFrom("users")
					.where("id=?", account.user.id)
					.executeNoResult();
			if(account.user.banInfo!=null){
				new SQLQueryBuilder(conn)
						.insertInto("deleted_user_bans")
						.value("user_id", account.user.id)
						.value("ban_status", account.user.banStatus)
						.value("ban_info", Utils.gson.toJson(account.user.banInfo))
						.executeNoResult();
			}
			removeFromCache(account.user);
			accountCache.remove(account.id);
		}
	}

	public static void removeAccountFromCache(int id){
		accountCache.remove(id);
	}

	public static void setUserBanStatus(User user, Account userAccount, UserBanStatus status, String banInfo) throws SQLException{
		new SQLQueryBuilder()
				.update("users")
				.value("ban_status", status)
				.value("ban_info", banInfo)
				.where("id=?", user.id)
				.executeNoResult();
		removeFromCache(user);
		if(userAccount!=null)
			accountCache.remove(userAccount.id);
	}

	public static List<User> getTerminallyBannedUsers() throws SQLException{
		return getByIdAsList(new SQLQueryBuilder()
				.selectFrom("users")
				.columns("id")
				.whereIn("ban_status", UserBanStatus.SELF_DEACTIVATED, UserBanStatus.SUSPENDED)
				.executeAndGetIntList());
	}

	public static boolean isUserMuted(int self, int id) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.columns("muted")
				.where("follower_id=? AND followee_id=?", self, id)
				.executeAndGetInt()==1;
	}

	public static void setUserMuted(int self, int id, boolean muted) throws SQLException{
		new SQLQueryBuilder()
				.update("followings")
				.value("muted", muted)
				.where("follower_id=? AND followee_id=?", self, id)
				.executeNoResult();
	}

	public static Map<Integer, UserPresence> getUserPresences(Collection<Integer> ids) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("users")
				.columns("id", "presence")
				.whereIn("id", ids)
				.andWhere("presence IS NOT NULL")
				.executeAsStream(r->new Pair<>(r.getInt("id"), Utils.gson.fromJson(r.getString("presence"), UserPresence.class)))
				.collect(Collectors.toMap(Pair::first, Pair::second));
	}

	public static void updateUserPresences(Map<Integer, UserPresence> presences) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			for(int id:presences.keySet()){
				new SQLQueryBuilder(conn)
						.update("users")
						.value("presence", Utils.gson.toJson(presences.get(id)))
						.where("id=?", id)
						.executeNoResult();
			}
		}
	}

	public static Set<Integer> getOnlineLocalUserIDs() throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("users")
				.columns("id")
				.where("is_online=1")
				.executeAndGetIntStream()
				.boxed()
				.collect(Collectors.toSet());
	}

	public static boolean incrementFriendHintsRank(int followerID, int followeeID, int amount) throws SQLException{
		return new SQLQueryBuilder()
				.update("followings")
				.valueExpr("hints_rank", "hints_rank+?", amount*1000)
				.where("follower_id=? AND followee_id=? AND mutual=1", followerID, followeeID)
				.executeUpdate()>0;
	}

	public static void normalizeFriendHintsRanksIfNeeded(Set<Integer> userIDs) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			List<Integer> filteredIDs=new SQLQueryBuilder(conn)
					.selectFrom("followings")
					.columns("follower_id")
					.whereIn("follower_id", userIDs)
					.groupBy("follower_id HAVING MAX(hints_rank)>500000")
					.executeAndGetIntList();
			for(int id:filteredIDs){
				new SQLQueryBuilder(conn)
						.update("followings")
						.where("follower_id=? AND mutual=1", id)
						.valueExpr("hints_rank", "FLOOR(hints_rank/2)")
						.executeNoResult();
			}
		}
	}

	public static Instant getUserLastFollowersTransferTime(int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("user_action_log")
				.columns("time")
				.where("user_id=? AND action=?", userID, UserActionLogAction.TRANSFER_FOLLOWERS)
				.orderBy("id DESC")
				.limit(1, 0)
				.executeAndGetSingleObject(r->DatabaseUtils.getInstant(r, "time"));
	}

	public static int createFriendList(int ownerID, String name, Collection<Integer> members) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			Set<Integer> existingListIDs=new SQLQueryBuilder(conn)
					.selectFrom("friend_lists")
					.columns("id")
					.where("owner_id=?", ownerID)
					.executeAndGetIntStream()
					.boxed()
					.collect(Collectors.toSet());
			int id=0;
			for(int i=1;i<FriendList.FIRST_PUBLIC_LIST_ID;i++){
				if(!existingListIDs.contains(i)){
					id=i;
					break;
				}
			}
			if(id==0)
				throw new SQLException("No available friend list IDs for this owner");
			new SQLQueryBuilder(conn)
					.insertInto("friend_lists")
					.value("id", id)
					.value("owner_id", ownerID)
					.value("name", name)
					.executeNoResult();

			if(!members.isEmpty()){
				addToFriendList(ownerID, id, members);
			}

			return id;
		}
	}

	public static void addToFriendList(int ownerID, int listID, Collection<Integer> members) throws SQLException{
		new SQLQueryBuilder()
				.update("followings")
				.whereIn("followee_id", members)
				.andWhere("follower_id=?", ownerID)
				.valueExpr("lists", "lists | ?", 1L << (listID-1))
				.executeNoResult();
	}

	public static void removeFromFriendList(int ownerID, int listID, Collection<Integer> members) throws SQLException{
		new SQLQueryBuilder()
				.update("followings")
				.whereIn("followee_id", members)
				.andWhere("follower_id=?", ownerID)
				.valueExpr("lists", "lists & (~(?))", 1L << (listID-1))
				.executeNoResult();
	}

	public static List<FriendList> getFriendLists(int ownerID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("friend_lists")
				.where("owner_id=?", ownerID)
				.orderBy("created_at ASC")
				.executeAsStream(FriendList::fromResultSet)
				.toList();
	}

	public static void deleteFriendList(int ownerID, int listID) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int rows=new SQLQueryBuilder(conn)
					.deleteFrom("friend_lists")
					.where("owner_id=? AND id=?", ownerID, listID)
					.executeUpdate();
			if(rows>0){
				new SQLQueryBuilder(conn)
						.update("followings")
						.where("follower_id=?", ownerID)
						.valueExpr("lists", "lists & (~(?))", 1L << (listID-1))
						.executeNoResult();
			}
		}
	}

	public static Map<Integer, BitSet> getFriendListsForUsers(int ownerID, Collection<Integer> userIDs) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.columns("followee_id", "lists")
				.whereIn("followee_id", userIDs)
				.andWhere("follower_id=?", ownerID)
				.executeAsStream(res->new Pair<>(res.getInt("followee_id"), BitSet.valueOf(new long[]{res.getLong("lists")})))
				.collect(Collectors.toMap(Pair::first, Pair::second));
	}

	public static void setFriendListsForUser(int ownerID, int userID, BitSet lists) throws SQLException{
		new SQLQueryBuilder()
				.update("followings")
				.where("follower_id=? AND followee_id=?", ownerID, userID)
				.value("lists", lists.isEmpty() ? 0 : lists.toLongArray()[0])
				.executeNoResult();
	}

	public static Set<Integer> getFriendListMemberIDs(int ownerID, int listID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.columns("followee_id")
				.where("follower_id=? AND mutual=1 AND (lists & ?)<>0", ownerID, 1L << (listID-1))
				.executeAndGetIntStream()
				.boxed()
				.collect(Collectors.toSet());
	}

	public static void renameFriendList(int ownerID, int listID, String name) throws SQLException{
		new SQLQueryBuilder()
				.update("friend_lists")
				.value("name", name)
				.where("owner_id=? AND id=?", ownerID, listID)
				.executeNoResult();
	}

	public static Map<Integer, BitSet> getAllFriendsWithLists(int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("followings")
				.columns("followee_id", "lists")
				.where("follower_id=? AND mutual=1 AND accepted=1", userID)
				.executeAsStream(res->new Pair<>(res.getInt(1), BitSet.valueOf(new long[]{res.getLong(2)})))
				.collect(Collectors.toMap(Pair::first, Pair::second));
	}
}

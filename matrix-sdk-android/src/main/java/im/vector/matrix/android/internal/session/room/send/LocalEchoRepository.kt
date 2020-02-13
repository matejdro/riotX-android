/*
 * Copyright 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.room.send

import com.zhuinden.monarchy.Monarchy
import dagger.Lazy
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.message.MessageContent
import im.vector.matrix.android.api.session.room.model.message.MessageType
import im.vector.matrix.android.api.session.room.send.SendState
import im.vector.matrix.android.api.session.room.timeline.TimelineEvent
import im.vector.matrix.android.internal.database.helper.nextId
import im.vector.matrix.android.internal.database.mapper.TimelineEventMapper
import im.vector.matrix.android.internal.database.mapper.asDomain
import im.vector.matrix.android.internal.database.mapper.toEntity
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.model.RoomEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntity
import im.vector.matrix.android.internal.database.query.findAllInRoomWithSendStates
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.session.room.RoomSummaryUpdater
import im.vector.matrix.android.internal.session.room.membership.RoomMemberHelper
import im.vector.matrix.android.internal.session.room.timeline.DefaultTimeline
import im.vector.matrix.android.internal.session.sync.RoomEventsProcessor
import im.vector.matrix.android.internal.session.sync.RoomEventsProcessors
import im.vector.matrix.android.internal.util.awaitTransaction
import io.realm.Realm
import org.greenrobot.eventbus.EventBus
import timber.log.Timber
import javax.inject.Inject

/**
 * A local echo is an event that is persisted even if not yet sent to the server,
 * in an optimistic way (as if the server as responded immediately). Local echo are using a local id,
 * (the transaction ID), this id is used when receiving an event from a sync to check if this event
 * is matching an existing local echo.
 *
 * The transactionID is used as local id.
 */
internal class LocalEchoRepository @Inject constructor(private val monarchy: Monarchy,
                                                       private val roomSummaryUpdater: RoomSummaryUpdater,
                                                       private val eventBus: EventBus,
                                                       private val timelineEventMapper: TimelineEventMapper,
                                                       private val roomEventsProcessors: RoomEventsProcessors) {

    suspend fun updateSendState(eventId: String, sendState: SendState) {
        Timber.v("Update local state of $eventId to ${sendState.name}")
        monarchy.awaitTransaction { realm ->
            val sendingEventEntity = EventEntity.where(realm, eventId).findFirst()
            if (sendingEventEntity != null) {
                if (sendState == SendState.SENT && sendingEventEntity.sendState == SendState.SYNCED) {
                    // If already synced, do not put as sent
                } else {
                    sendingEventEntity.sendState = sendState
                }
            }
        }
    }

    suspend fun createLocalEcho(event: Event) {
        val roomId = event.roomId ?: throw IllegalStateException("You should have set a roomId for your event")
        val senderId = event.senderId ?: throw IllegalStateException("You should have set a senderIf for your event")
        if (event.eventId == null) {
            throw IllegalStateException("You should have set an eventId for your event")
        }
        val timelineEventEntity = Realm.getInstance(monarchy.realmConfiguration).use { realm ->
            val eventEntity = event.toEntity(roomId, SendState.UNSENT)
            val roomMemberHelper = RoomMemberHelper(realm, roomId)
            val myUser = roomMemberHelper.getLastRoomMember(senderId)
            val localId = TimelineEventEntity.nextId(realm)
            TimelineEventEntity(localId).also {
                it.root = eventEntity
                it.eventId = event.eventId
                it.roomId = roomId
                it.senderName = myUser?.displayName
                it.senderAvatar = myUser?.avatarUrl
                it.isUniqueDisplayName = roomMemberHelper.isUniqueDisplayName(myUser?.displayName)
            }
        }
        val timelineEvent = timelineEventMapper.map(timelineEventEntity)
        eventBus.post(DefaultTimeline.OnLocalEchoCreated(roomId = roomId, timelineEvent = timelineEvent))
        monarchy.awaitTransaction { realm ->
            val roomEntity = RoomEntity.where(realm, roomId = roomId).findFirst() ?: return@awaitTransaction
            roomEntity.sendingTimelineEvents.add(0, timelineEventEntity)
            roomSummaryUpdater.update(realm, roomId)
        }
        roomEventsProcessors.process(RoomEventsProcessor.Mode.LOCAL_ECHO, roomId, listOf(event))
    }

    suspend fun deleteFailedEcho(roomId: String, localEcho: TimelineEvent) {
        monarchy.awaitTransaction { realm ->
            TimelineEventEntity.where(realm, roomId = roomId, eventId = localEcho.root.eventId ?: "").findFirst()?.deleteFromRealm()
            EventEntity.where(realm, eventId = localEcho.root.eventId ?: "").findFirst()?.deleteFromRealm()
        }
    }

    suspend fun clearSendingQueue(roomId: String) {
        monarchy.awaitTransaction { realm ->
            RoomEntity.where(realm, roomId).findFirst()?.let { room ->
                room.sendingTimelineEvents.forEach {
                    it.root?.sendState = SendState.UNDELIVERED
                }
            }
        }
    }

    suspend fun updateSendState(roomId: String, eventIds: List<String>, sendState: SendState) {
        monarchy.awaitTransaction { realm ->
            val timelineEvents = TimelineEventEntity.where(realm, roomId, eventIds).findAll()
            timelineEvents.forEach {
                it.root?.sendState = sendState
            }
        }
    }

    fun getAllFailedEventsToResend(roomId: String): List<Event> {
        return Realm.getInstance(monarchy.realmConfiguration).use { realm ->
            TimelineEventEntity
                    .findAllInRoomWithSendStates(realm, roomId, SendState.HAS_FAILED_STATES)
                    .sortedByDescending { it.displayIndex }
                    .mapNotNull { it.root?.asDomain() }
                    .filter { event ->
                        when (event.getClearType()) {
                            EventType.MESSAGE,
                            EventType.REDACTION,
                            EventType.REACTION -> {
                                val content = event.getClearContent().toModel<MessageContent>()
                                if (content != null) {
                                    when (content.msgType) {
                                        MessageType.MSGTYPE_EMOTE,
                                        MessageType.MSGTYPE_NOTICE,
                                        MessageType.MSGTYPE_LOCATION,
                                        MessageType.MSGTYPE_TEXT  -> {
                                            true
                                        }
                                        MessageType.MSGTYPE_FILE,
                                        MessageType.MSGTYPE_VIDEO,
                                        MessageType.MSGTYPE_IMAGE,
                                        MessageType.MSGTYPE_AUDIO -> {
                                            // need to resend the attachment
                                            false
                                        }
                                        else                      -> {
                                            Timber.e("Cannot resend message ${event.type} / ${content.msgType}")
                                            false
                                        }
                                    }
                                } else {
                                    Timber.e("Unsupported message to resend ${event.type}")
                                    false
                                }
                            }
                            else               -> {
                                Timber.e("Unsupported message to resend ${event.type}")
                                false
                            }
                        }
                    }
        }
    }
}

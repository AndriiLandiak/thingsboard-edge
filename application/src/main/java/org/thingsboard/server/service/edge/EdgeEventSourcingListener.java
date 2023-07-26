/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.edge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.api.EmptyNodeConfiguration;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.OtaPackageInfo;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.alarm.AlarmApiCallResult;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.edge.EdgeEventType;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.edge.EdgeSynchronizationManager;
import org.thingsboard.server.dao.eventsourcing.ActionEntityEvent;
import org.thingsboard.server.dao.eventsourcing.ActionRelationEvent;
import org.thingsboard.server.dao.eventsourcing.DeleteEntityEvent;
import org.thingsboard.server.dao.eventsourcing.SaveEntityEvent;

import javax.annotation.PostConstruct;

import static org.thingsboard.server.service.entitiy.DefaultTbNotificationEntityService.edgeTypeByActionType;

/**
 * This event listener does not support async event processing because relay on ThreadLocal
 * Another possible approach is to implement a special annotation and a bunch of classes similar to TransactionalApplicationListener
 * This class is the simplest approach to maintain edge synchronization within the single class.
 * <p>
 * For async event publishers, you have to decide whether publish event on creating async task in the same thread where dao method called
 * @Autowired
 * EdgeEventSynchronizationManager edgeSynchronizationManager
 * ...
 *   //some async write action make future
 *   if (!edgeSynchronizationManager.isSync()) {
 *     future.addCallback(eventPublisher.publishEvent(...))
 *   }
 * */
@Component
@RequiredArgsConstructor
@Slf4j
public class EdgeEventSourcingListener {

    private final TbClusterService tbClusterService;
    private final EdgeSynchronizationManager edgeSynchronizationManager;

    @PostConstruct
    public void init() {
        log.info("EdgeEventSourcingListener initiated");
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void handleEvent(SaveEntityEvent<?> event) {
        if (edgeSynchronizationManager.isSync()) {
            return;
        }
        if (!isValidEdgeEventEntity(event.getEntity())) {
            return;
        }
        log.trace("[{}] SaveEntityEvent called: {}", event.getEntityId().getEntityType(), event);
        EdgeEventActionType action = Boolean.TRUE.equals(event.getAdded()) ? EdgeEventActionType.ADDED : EdgeEventActionType.UPDATED;
        tbClusterService.sendNotificationMsgToEdge(event.getTenantId(), null, event.getEntityId(),
                null, null, action);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void handleEvent(DeleteEntityEvent event) {
        if (edgeSynchronizationManager.isSync()) {
            return;
        }
        log.trace("[{}] DeleteEntityEvent called: {}", event.getEntityId().getEntityType(), event);
        tbClusterService.sendNotificationMsgToEdge(event.getTenantId(), event.getEdgeId(), event.getEntityId(),
                event.getBody(), null, EdgeEventActionType.DELETED);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void handleEvent(ActionEntityEvent event) {
        if (edgeSynchronizationManager.isSync()) {
            return;
        }
        if (ActionType.RELATION_DELETED.equals(event.getActionType())) {
            EntityRelation relation = JacksonUtil.fromString(event.getBody(), EntityRelation.class);
            if (relation == null) {
                log.trace("skipping RELATION_DELETED event in case relation is null: {}", event);
                return;
            }
            if (!RelationTypeGroup.COMMON.equals(relation.getTypeGroup())) {
                log.trace("skipping RELATION_DELETED event in case NOT COMMON relation type group: {}", event);
                return;
            }
        }
        log.trace("[{}] ActionEntityEvent called: {}", event.getEntityId().getEntityType(), event);
        tbClusterService.sendNotificationMsgToEdge(event.getTenantId(), event.getEdgeId(), event.getEntityId(),
                event.getBody(), null, edgeTypeByActionType(event.getActionType()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void handleEvent(ActionRelationEvent event) {
        if (edgeSynchronizationManager.isSync()) {
            return;
        }
        EntityRelation relation = JacksonUtil.fromString(event.getBody(), EntityRelation.class);
        if (relation == null) {
            log.trace("skipping ActionRelationEvent event in case relation is null: {}", event);
            return;
        }
        if (!RelationTypeGroup.COMMON.equals(relation.getTypeGroup())) {
            log.trace("skipping ActionRelationEvent event in case NOT COMMON relation type group: {}", event);
            return;
        }
        log.trace("ActionRelationEvent called: {}", event);
        tbClusterService.sendNotificationMsgToEdge(event.getTenantId(), null, null,
                event.getBody(), EdgeEventType.RELATION, edgeTypeByActionType(event.getActionType()));
    }

    private boolean isValidEdgeEventEntity(Object entity) {
        if (entity instanceof OtaPackageInfo) {
            OtaPackageInfo otaPackageInfo = (OtaPackageInfo) entity;
            return otaPackageInfo.hasUrl() || otaPackageInfo.isHasData();
        } else if (entity instanceof RuleChain) {
            RuleChain ruleChain = (RuleChain) entity;
            return RuleChainType.EDGE.equals(ruleChain.getType());
        } else if (entity instanceof User) {
            User user = (User) entity;
            return !Authority.SYS_ADMIN.equals(user.getAuthority());
        } else if (entity instanceof AlarmApiCallResult) {
            AlarmApiCallResult alarmApiCallResult = (AlarmApiCallResult) entity;
            return alarmApiCallResult.isModified();
        }
        // Default: If the entity doesn't match any of the conditions, consider it as valid.
        return true;
    }
}

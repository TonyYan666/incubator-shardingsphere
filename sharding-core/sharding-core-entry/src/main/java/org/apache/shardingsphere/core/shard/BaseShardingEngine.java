/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.core.shard;

import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.api.hint.HintManager;
import org.apache.shardingsphere.core.route.SQLLogger;
import org.apache.shardingsphere.core.route.ShardingRouteResult;
import org.apache.shardingsphere.core.route.hook.SPIRoutingHook;
import org.apache.shardingsphere.core.route.type.RoutingUnit;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.encrypt.rewrite.context.EncryptSQLRewriteContextDecorator;
import org.apache.shardingsphere.sharding.rewrite.context.ShardingSQLRewriteContextDecorator;
import org.apache.shardingsphere.sharding.rewrite.engine.ShardingSQLRewriteEngine;
import org.apache.shardingsphere.underlying.common.constant.properties.PropertiesConstant;
import org.apache.shardingsphere.underlying.common.constant.properties.ShardingSphereProperties;
import org.apache.shardingsphere.underlying.common.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.underlying.common.rule.BaseRule;
import org.apache.shardingsphere.underlying.rewrite.SQLRewriteEntry;
import org.apache.shardingsphere.underlying.rewrite.context.SQLRewriteContext;
import org.apache.shardingsphere.underlying.rewrite.context.SQLRewriteContextDecorator;
import org.apache.shardingsphere.underlying.rewrite.engine.SQLRewriteResult;
import org.apache.shardingsphere.underlying.route.RouteUnit;
import org.apache.shardingsphere.underlying.route.SQLUnit;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Base sharding engine.
 *
 * @author zhangliang
 * @author panjuan
 */
@RequiredArgsConstructor
public abstract class BaseShardingEngine {
    
    private final ShardingRule shardingRule;
    
    private final ShardingSphereProperties properties;
    
    private final ShardingSphereMetaData metaData;
    
    private final SPIRoutingHook routingHook = new SPIRoutingHook();
    
    /**
     * Shard.
     *
     * @param sql SQL
     * @param parameters parameters of SQL
     * @return SQL route result
     */
    public ShardingRouteResult shard(final String sql, final List<Object> parameters) {
        List<Object> clonedParameters = cloneParameters(parameters);
        ShardingRouteResult result = executeRoute(sql, clonedParameters);
        result.getRouteUnits().addAll(HintManager.isDatabaseShardingOnly() ? convert(sql, clonedParameters, result) : rewriteAndConvert(sql, clonedParameters, result));
        boolean showSQL = properties.getValue(PropertiesConstant.SQL_SHOW);
        if (showSQL) {
            boolean showSimple = properties.getValue(PropertiesConstant.SQL_SIMPLE);
            SQLLogger.logSQL(sql, showSimple, result.getSqlStatementContext(), result.getRouteUnits());
        }
        return result;
    }
    
    protected abstract List<Object> cloneParameters(List<Object> parameters);
    
    protected abstract ShardingRouteResult route(String sql, List<Object> parameters);
    
    private ShardingRouteResult executeRoute(final String sql, final List<Object> clonedParameters) {
        routingHook.start(sql);
        try {
            ShardingRouteResult result = route(sql, clonedParameters);
            routingHook.finishSuccess(result, metaData.getTables());
            return result;
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            routingHook.finishFailure(ex);
            throw ex;
        }
    }
    
    private Collection<RouteUnit> convert(final String sql, final List<Object> parameters, final ShardingRouteResult shardingRouteResult) {
        Collection<RouteUnit> result = new LinkedHashSet<>();
        for (RoutingUnit each : shardingRouteResult.getRoutingResult().getRoutingUnits()) {
            result.add(new RouteUnit(each.getActualDataSourceName(), new SQLUnit(sql, parameters)));
        }
        return result;
    }
    
    private Collection<RouteUnit> rewriteAndConvert(final String sql, final List<Object> parameters, final ShardingRouteResult shardingRouteResult) {
        Collection<RouteUnit> result = new LinkedHashSet<>();
        SQLRewriteContext sqlRewriteContext = new SQLRewriteEntry(
                metaData, properties).createSQLRewriteContext(sql, parameters, shardingRouteResult.getSqlStatementContext(), createSQLRewriteContextDecorator(shardingRouteResult));
        for (RoutingUnit each : shardingRouteResult.getRoutingResult().getRoutingUnits()) {
            ShardingSQLRewriteEngine sqlRewriteEngine = new ShardingSQLRewriteEngine(shardingRule, shardingRouteResult.getShardingConditions(), each);
            SQLRewriteResult sqlRewriteResult = sqlRewriteEngine.rewrite(sqlRewriteContext);
            result.add(new RouteUnit(each.getActualDataSourceName(), new SQLUnit(sqlRewriteResult.getSql(), sqlRewriteResult.getParameters())));
        }
        return result;
    }
    
    private Map<BaseRule, SQLRewriteContextDecorator> createSQLRewriteContextDecorator(final ShardingRouteResult sqlRouteResult) {
        Map<BaseRule, SQLRewriteContextDecorator> result = new LinkedHashMap<>(2, 1);
        result.put(shardingRule, new ShardingSQLRewriteContextDecorator(sqlRouteResult));
        if (shardingRule.getEncryptRule().getEncryptTableNames().isEmpty()) {
            result.put(shardingRule.getEncryptRule(), new EncryptSQLRewriteContextDecorator());
        }
        return result;
    }
}

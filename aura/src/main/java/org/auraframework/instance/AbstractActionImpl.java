/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.instance;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.auraframework.def.ActionDef;
import org.auraframework.def.ControllerDef;
import org.auraframework.def.DefDescriptor;

import org.auraframework.system.LoggingContext.KeyValueLogger;
import org.auraframework.throwable.AuraRuntimeException;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public abstract class AbstractActionImpl<T extends ActionDef> implements Action {
    public AbstractActionImpl(DefDescriptor<ControllerDef> controllerDescriptor, T actionDef,
            Map<String, Object> paramValues) {
        this.state = State.NEW;
        this.actionDef = actionDef;
        this.controllerDescriptor = controllerDescriptor;
        this.paramValues = paramValues;
    }

    @Override
    public String getId() {
        return this.actionId;
    }

    @Override
    public void setId(String id) {
        //
        // We _MUST NOT_ have a current stack when ID is set.
        //
        if (instanceStack != null) {
            throw new AuraRuntimeException("Already have an instance stack when ID is set");
        }
        actionId = id;
    }

    @Override
    public void add(List<Action> newActions) {
        if (actions == null) {
            actions = Lists.newArrayList();
        }
        actions.addAll(newActions);
    }

    @Override
    public List<Action> getActions() {
        if (actions == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(actions);
    }

    //public Object getReturnValue();

    @Override
    public State getState() {
        return this.state;
    }

    //public List<Object> getErrors();

    @Override
    public void registerComponent(BaseComponent<?, ?> component) {
        if (componentRegistry == null) {
            componentRegistry = Maps.newLinkedHashMap();
        }
        //
        // This following assertion should work, but default attributes and
        // providers setting attributes can break this.
        //
        if (componentRegistry.containsKey(component.getPath())) {
            //throw new AuraRuntimeException("duplicate component path"+component.getPath());
        }
        componentRegistry.put(component.getPath(), component);
    }

    @Override
    public Map<String, BaseComponent<?, ?>> getComponents() {
        if (componentRegistry == null) {
            return Collections.emptyMap();
        }
        return componentRegistry;
    }

    @Override
    public int getNextId() {
        return nextId++;
    }

    @Override
    public DefDescriptor<ActionDef> getDescriptor() {
        return actionDef.getDescriptor();
    }

    @Override
    public Map<String, Object> getParams() {
        return paramValues;
    }

    @Override
    public boolean isStorable() {
        return storable;
    }

    @Override
    public void setStorable() {
        storable = true;
        setId("s");
    }

    @Override
    public String toString() {
        return String.format("%s.%s", controllerDescriptor.toString(), actionDef.getName());
    }

    /**
     * Log any params that are useful and safe to log.
     * @param paramLogger
     */
    @Override
    public void logParams(KeyValueLogger logger) {
        List<String> loggableParams = actionDef.getLoggableParams();
        if (paramValues != null && loggableParams != null) {
            for (String paramName : loggableParams) {
                logger.log(paramName, String.valueOf(paramValues.get(paramName)));
            }
        }
    }
    
    @Override
    public InstanceStack getInstanceStack() {
        if (instanceStack == null) {
            //
            // This should never happen, but there are some tests that fail to
            // initialize the ID. This led to a null pointer exception. Here we
            // force the action ID to a non-null, meaning that setId will now
            // fail.
            //
            if (actionId == null) {
                actionId = "unknown";
            }
            instanceStack = new InstanceStack();
        }
        return instanceStack;
    }

    @Override
    public String getPath() {
        return actionId;
    }

    private String actionId;
    private List<Action> actions = null;
    private int nextId = 1;
    private boolean storable;

    protected Map<String, BaseComponent<?, ?>> componentRegistry = null;
    protected final Map<String, Object> paramValues;
    protected final DefDescriptor<ControllerDef> controllerDescriptor;
    protected final T actionDef;
    protected State state;
    private InstanceStack instanceStack;
}

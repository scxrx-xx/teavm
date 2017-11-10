/*
 *  Copyright 2012 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.dependency;

import java.util.*;
import org.teavm.model.MethodReference;

public class DependencyNode implements ValueDependencyInfo {
    private static final int SMALL_TYPES_THRESHOLD = 6;
    private DependencyChecker dependencyChecker;
    private List<DependencyConsumer> followers;
    private int[] smallTypes;
    private BitSet types;
    private List<DependencyNodeToNodeTransition> transitions;
    private volatile String tag;
    private DependencyNode arrayItemNode;
    private DependencyNode classValueNode;
    private int degree;
    boolean locked;
    MethodReference method;

    DependencyNode(DependencyChecker dependencyChecker) {
        this(dependencyChecker, 0);
    }

    private DependencyNode(DependencyChecker dependencyChecker, int degree) {
        this.dependencyChecker = dependencyChecker;
        this.degree = degree;
    }

    private boolean addType(DependencyType type) {
        if (types == null) {
            if (smallTypes == null) {
                if (locked) {
                    throw new IllegalStateException("Error propagating type " + type.getName()
                            + " to node in " + method);
                }
                smallTypes = new int[] { type.index };
                return true;
            }
        }
        if (smallTypes != null) {
            for (int i = 0; i < smallTypes.length; ++i) {
                if (smallTypes[i] == type.index) {
                    return false;
                }
            }
            if (smallTypes.length == SMALL_TYPES_THRESHOLD) {
                types = new BitSet(dependencyChecker.types.size() * 2);
                for (int existingType : smallTypes) {
                    types.set(existingType);
                }
                smallTypes = null;
            } else {
                if (locked) {
                    throw new IllegalStateException("Error propagating type " + type.getName() + " to node in method "
                            + method);
                }
                smallTypes = Arrays.copyOf(smallTypes, smallTypes.length + 1);
                smallTypes[smallTypes.length - 1] = type.index;
                return true;
            }
        }
        if (!types.get(type.index)) {
            if (locked) {
                throw new IllegalStateException("Error propagating type " + type.getName() + " to node " + tag);
            }
            types.set(type.index);
            return true;
        }
        return false;
    }

    public void propagate(DependencyType type) {
        if (degree > 2) {
            return;
        }
        if (addType(type)) {
            if (DependencyChecker.shouldLog) {
                System.out.println(tag + " -> " + type.getName());
            }
            scheduleSingleType(type);
        }
    }

    private void scheduleSingleType(DependencyType type) {
        if (followers != null) {
            for (DependencyConsumer consumer : followers.toArray(new DependencyConsumer[followers.size()])) {
                dependencyChecker.schedulePropagation(consumer, type);
            }
        }
        if (transitions != null) {
            for (DependencyNodeToNodeTransition consumer : transitions.toArray(
                    new DependencyNodeToNodeTransition[transitions.size()])) {
                dependencyChecker.schedulePropagation(consumer, type);
            }
        }
    }

    public void propagate(DependencyType[] newTypes) {
        if (degree > 2) {
            return;
        }

        int j = 0;
        for (int i = 0; i < newTypes.length; ++i) {
            DependencyType type = newTypes[i];
            if (addType(type)) {
                newTypes[j++] = type;
            }
        }
        if (j == 0) {
            return;
        }
        if (DependencyChecker.shouldLog) {
            for (int i = 0; i < j; ++i) {
                System.out.println(tag + " -> " + newTypes[i].getName());
            }
        }

        if (followers == null && transitions == null) {
            return;
        }
        if (j < newTypes.length) {
            if (j == 1) {
                scheduleSingleType(newTypes[0]);
                return;
            }
            newTypes = Arrays.copyOf(newTypes, j);
        }
        if (followers != null) {
            for (DependencyConsumer consumer : followers.toArray(new DependencyConsumer[followers.size()])) {
                dependencyChecker.schedulePropagation(consumer, newTypes);
            }
        }
        if (transitions != null) {
            for (DependencyNodeToNodeTransition consumer : transitions.toArray(
                    new DependencyNodeToNodeTransition[transitions.size()])) {
                dependencyChecker.schedulePropagation(consumer, newTypes);
            }
        }
    }

    public void addConsumer(DependencyConsumer consumer) {
        if (followers == null) {
            followers = new ArrayList<>(1);
        }
        if (followers.contains(consumer)) {
            return;
        }
        followers.add(consumer);

        propagateTypes(consumer, null);
    }

    public void connect(DependencyNode node, DependencyTypeFilter filter) {
        if (this == node) {
            return;
        }
        if (node == null) {
            throw new IllegalArgumentException("Node must not be null");
        }
        if (transitions != null) {
            for (DependencyNodeToNodeTransition transition : transitions) {
                if (transition.destination == node) {
                    return;
                }
            }
        }
        DependencyNodeToNodeTransition transition = new DependencyNodeToNodeTransition(this, node, filter);
        if (transitions == null) {
            transitions = new ArrayList<>(1);
        }

        transitions.add(transition);
        if (DependencyChecker.shouldLog) {
            System.out.println("Connecting " + tag + " to " + node.tag);
        }

        propagateTypes(transition, filter);
    }

    private void propagateTypes(DependencyConsumer transition, DependencyTypeFilter filter) {
        if (this.types != null) {
            List<DependencyType> types = new ArrayList<>();
            for (int index = this.types.nextSetBit(0); index >= 0; index = this.types.nextSetBit(index + 1)) {
                DependencyType type = dependencyChecker.types.get(index);
                if (filter == null || filter.match(type)) {
                    types.add(type);
                }
            }
            dependencyChecker.schedulePropagation(transition, types.toArray(new DependencyType[types.size()]));
        } else if (this.smallTypes != null) {
            DependencyType[] types = new DependencyType[smallTypes.length];
            int j = 0;
            for (int i = 0; i < types.length; ++i) {
                DependencyType type = dependencyChecker.types.get(smallTypes[i]);
                if (filter == null || filter.match(type)) {
                    types[j++] = type;
                }
            }
            if (j < types.length) {
                types = Arrays.copyOf(types, j);
            }
            dependencyChecker.schedulePropagation(transition, types);
        }
    }

    public void connect(DependencyNode node) {
        connect(node, null);
    }

    @Override
    public DependencyNode getArrayItem() {
        if (arrayItemNode == null) {
            arrayItemNode = new DependencyNode(dependencyChecker, degree + 1);
            if (DependencyChecker.shouldLog) {
                arrayItemNode.tag = tag + "[";
            }
        }
        return arrayItemNode;
    }

    @Override
    public DependencyNode getClassValueNode() {
        if (classValueNode == null) {
            classValueNode = new DependencyNode(dependencyChecker, degree);
            classValueNode.classValueNode = classValueNode;
            if (DependencyChecker.shouldLog) {
                classValueNode.tag = tag + "@";
            }
        }
        return classValueNode;
    }

    @Override
    public boolean hasArrayType() {
        return arrayItemNode != null && (arrayItemNode.types != null || arrayItemNode.smallTypes != null);
    }

    public boolean hasType(DependencyType type) {
        if (smallTypes != null) {
            for (int i = 0; i < smallTypes.length; ++i) {
                if (smallTypes[i] == type.index) {
                    return true;
                }
            }
            return false;
        }
        return types != null && types.get(type.index);
    }

    @Override
    public boolean hasType(String type) {
        return hasType(dependencyChecker.getType(type));
    }

    @Override
    public String[] getTypes() {
        if (smallTypes != null) {
            String[] result = new String[smallTypes.length];
            for (int i = 0; i < result.length; ++i) {
                result[i] = dependencyChecker.types.get(smallTypes[i]).getName();
            }
            return result;
        }
        if (types == null) {
            return new String[0];
        }
        List<String> result = new ArrayList<>();
        for (int index = types.nextSetBit(0); index >= 0; index = types.nextSetBit(index + 1)) {
            result.add(dependencyChecker.types.get(index).getName());
        }
        return result.toArray(new String[result.size()]);
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }
}

/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

final class SimpleConfigList extends AbstractConfigValue implements ConfigList {

    final private List<AbstractConfigValue> value;
    final private boolean resolved;

    SimpleConfigList(ConfigOrigin origin, List<AbstractConfigValue> value) {
        this(origin, value, ResolveStatus.fromValues(value));
    }

    SimpleConfigList(ConfigOrigin origin, List<AbstractConfigValue> value,
            ResolveStatus status) {
        super(origin);
        this.value = value;
        this.resolved = status == ResolveStatus.RESOLVED;
    }

    @Override
    public ConfigValueType valueType() {
        return ConfigValueType.LIST;
    }

    @Override
    public List<Object> unwrapped() {
        List<Object> list = new ArrayList<Object>();
        for (AbstractConfigValue v : value) {
            list.add(v.unwrapped());
        }
        return list;
    }

    @Override
    ResolveStatus resolveStatus() {
        return ResolveStatus.fromBoolean(resolved);
    }

    private SimpleConfigList modify(Modifier modifier,
            ResolveStatus newResolveStatus) {
        // lazy-create for optimization
        List<AbstractConfigValue> changed = null;
        int i = 0;
        for (AbstractConfigValue v : value) {
            AbstractConfigValue modified = modifier.modifyChild(v);

            // lazy-create the new list if required
            if (changed == null && modified != v) {
                changed = new ArrayList<AbstractConfigValue>();
                for (int j = 0; j < i; ++j) {
                    changed.add(value.get(j));
                }
            }

            // once the new list is created, all elements
            // have to go in it. if modifyChild returned
            // null, we drop that element.
            if (changed != null && modified != null) {
                changed.add(modified);
            }

            i += 1;
        }

        if (changed != null) {
            return new SimpleConfigList(origin(), changed, newResolveStatus);
        } else {
            return this;
        }
    }

    @Override
    SimpleConfigList resolveSubstitutions(final SubstitutionResolver resolver,
            final int depth, final ConfigResolveOptions options) {
        if (resolved)
            return this;

        return modify(new Modifier() {
            @Override
            public AbstractConfigValue modifyChild(AbstractConfigValue v) {
                return resolver.resolve(v, depth, options);
            }

        }, ResolveStatus.RESOLVED);
    }

    @Override
    SimpleConfigList relativized(final Path prefix) {
        return modify(new Modifier() {
            @Override
            public AbstractConfigValue modifyChild(AbstractConfigValue v) {
                return v.relativized(prefix);
            }

        }, resolveStatus());
    }

    @Override
    protected boolean canEqual(Object other) {
        return other instanceof SimpleConfigList;
    }

    @Override
    public boolean equals(Object other) {
        // note that "origin" is deliberately NOT part of equality
        if (other instanceof SimpleConfigList) {
            // optimization to avoid unwrapped() for two ConfigList
            return canEqual(other) && value.equals(((SimpleConfigList) other).value);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        // note that "origin" is deliberately NOT part of equality
        return value.hashCode();
    }

    @Override
    protected void render(StringBuilder sb, int indent, boolean formatted) {
        if (value.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[");
            if (formatted)
                sb.append('\n');
            for (AbstractConfigValue v : value) {
                if (formatted) {
                    indent(sb, indent + 1);
                    sb.append("# ");
                    sb.append(v.origin().description());
                    sb.append("\n");

                    for (String comment : v.origin().comments()) {
                        indent(sb, indent + 1);
                        sb.append("# ");
                        sb.append(comment);
                        sb.append("\n");
                    }

                    indent(sb, indent + 1);
                }
                v.render(sb, indent + 1, formatted);
                sb.append(",");
                if (formatted)
                    sb.append('\n');
            }
            sb.setLength(sb.length() - 1); // chop or newline
            if (formatted) {
                sb.setLength(sb.length() - 1); // also chop comma
                sb.append('\n');
                indent(sb, indent);
            }
            sb.append("]");
        }
    }

    @Override
    public boolean contains(Object o) {
        return value.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return value.containsAll(c);
    }

    @Override
    public AbstractConfigValue get(int index) {
        return value.get(index);
    }

    @Override
    public int indexOf(Object o) {
        return value.indexOf(o);
    }

    @Override
    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public Iterator<ConfigValue> iterator() {
        final Iterator<AbstractConfigValue> i = value.iterator();

        return new Iterator<ConfigValue>() {
            @Override
            public boolean hasNext() {
                return i.hasNext();
            }

            @Override
            public ConfigValue next() {
                return i.next();
            }

            @Override
            public void remove() {
                throw weAreImmutable("iterator().remove");
            }
        };
    }

    @Override
    public int lastIndexOf(Object o) {
        return value.lastIndexOf(o);
    }

    private static ListIterator<ConfigValue> wrapListIterator(
            final ListIterator<AbstractConfigValue> i) {
        return new ListIterator<ConfigValue>() {
            @Override
            public boolean hasNext() {
                return i.hasNext();
            }

            @Override
            public ConfigValue next() {
                return i.next();
            }

            @Override
            public void remove() {
                throw weAreImmutable("listIterator().remove");
            }

            @Override
            public void add(ConfigValue arg0) {
                throw weAreImmutable("listIterator().add");
            }

            @Override
            public boolean hasPrevious() {
                return i.hasPrevious();
            }

            @Override
            public int nextIndex() {
                return i.nextIndex();
            }

            @Override
            public ConfigValue previous() {
                return i.previous();
            }

            @Override
            public int previousIndex() {
                return i.previousIndex();
            }

            @Override
            public void set(ConfigValue arg0) {
                throw weAreImmutable("listIterator().set");
            }
        };
    }

    @Override
    public ListIterator<ConfigValue> listIterator() {
        return wrapListIterator(value.listIterator());
    }

    @Override
    public ListIterator<ConfigValue> listIterator(int index) {
        return wrapListIterator(value.listIterator(index));
    }

    @Override
    public int size() {
        return value.size();
    }

    @Override
    public List<ConfigValue> subList(int fromIndex, int toIndex) {
        List<ConfigValue> list = new ArrayList<ConfigValue>();
        // yay bloat caused by lack of type variance
        for (AbstractConfigValue v : value.subList(fromIndex, toIndex)) {
            list.add(v);
        }
        return list;
    }

    @Override
    public Object[] toArray() {
        return value.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return value.toArray(a);
    }

    private static UnsupportedOperationException weAreImmutable(String method) {
        return new UnsupportedOperationException(
                "ConfigList is immutable, you can't call List.'" + method + "'");
    }

    @Override
    public boolean add(ConfigValue e) {
        throw weAreImmutable("add");
    }

    @Override
    public void add(int index, ConfigValue element) {
        throw weAreImmutable("add");
    }

    @Override
    public boolean addAll(Collection<? extends ConfigValue> c) {
        throw weAreImmutable("addAll");
    }

    @Override
    public boolean addAll(int index, Collection<? extends ConfigValue> c) {
        throw weAreImmutable("addAll");
    }

    @Override
    public void clear() {
        throw weAreImmutable("clear");
    }

    @Override
    public boolean remove(Object o) {
        throw weAreImmutable("remove");
    }

    @Override
    public ConfigValue remove(int index) {
        throw weAreImmutable("remove");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw weAreImmutable("removeAll");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw weAreImmutable("retainAll");
    }

    @Override
    public ConfigValue set(int index, ConfigValue element) {
        throw weAreImmutable("set");
    }

    @Override
    protected SimpleConfigList newCopy(boolean ignoresFallbacks, ConfigOrigin newOrigin) {
        return new SimpleConfigList(newOrigin, value);
    }
}

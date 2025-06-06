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
package org.apache.solr.common.params;

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.MapWriter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;

/**
 * SolrParams is designed to hold parameters to Solr, often from the request coming into Solr. It's
 * basically a MultiMap of String keys to one or more String values. Neither keys nor values may be
 * null. Unlike a general Map/MultiMap, the size is unknown without iterating over each parameter
 * name, if you want to count the different values for a key separately.
 */
public abstract class SolrParams
    implements Serializable, MapWriter, Iterable<Map.Entry<String, String[]>> {

  /**
   * Returns the first String value of a param, or null if not set. To get all, call {@link
   * #getParams(String)} instead.
   */
  public abstract String get(String param);

  /**
   * returns an array of the String values of a param, or null if no mapping for the param exists.
   */
  public abstract String[] getParams(String param);

  /**
   * Returns an Iterator over the parameter names. If you were to call a getter for this parameter,
   * you should get a non-null value. Since you probably want the value, consider using Java 5
   * for-each style instead for convenience since a SolrParams implements {@link Iterable}.
   */
  public abstract Iterator<String> getParameterNamesIterator();

  /** returns the value of the param, or def if not set */
  public String get(String param, String def) {
    String val = get(param);
    return val == null ? def : val;
  }

  @Override
  public void writeMap(EntryWriter ew) throws IOException {
    for (Entry<String, String[]> entry : this) {
      String[] value = entry.getValue();
      // if only one value, don't wrap in an array
      if (value.length == 1) {
        assert value[0] != null;
        ew.put(entry.getKey(), value[0]);
      } else if (value.length > 1) {
        // values shouldn't be null; not bothering to assert it
        ew.put(entry.getKey(), value);
      }
    }
  }

  /** Returns an Iterator of {@code Map.Entry} providing a multi-map view. Treat it as read-only. */
  @Override
  public Iterator<Map.Entry<String, String[]>> iterator() {
    Iterator<String> it = getParameterNamesIterator();
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public Map.Entry<String, String[]> next() {
        String key = it.next();
        return new Map.Entry<>() {
          @Override
          public String getKey() {
            return key;
          }

          @Override
          public String[] getValue() {
            return getParams(key);
          }

          @Override
          public String[] setValue(String[] newValue) {
            throw new UnsupportedOperationException("read-only");
          }

          @Override
          public String toString() {
            return getKey() + "=" + Arrays.toString(getValue());
          }
        };
      }
    };
  }

  /** A {@link Stream} view over {@link #iterator()} -- for convenience. Treat it as read-only. */
  public Stream<Map.Entry<String, String[]>> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  // Do we add Map.forEach equivalent too?  But it eager-fetches the value, and Iterable<Map.Entry>
  // allows the user to only get the value when needed.

  /** returns a RequiredSolrParams wrapping this */
  public RequiredSolrParams required() {
    // TODO? should we want to stash a reference?
    return new RequiredSolrParams(this);
  }

  protected String fpname(String field, String param) {
    return "f." + field + '.' + param;
  }

  /**
   * returns the String value of the field parameter, "f.field.param", or the value for "param" if
   * that is not set.
   */
  public String getFieldParam(String field, String param) {
    String val = get(fpname(field, param));
    return val != null ? val : get(param);
  }

  /**
   * returns the String value of the field parameter, "f.field.param", or the value for "param" if
   * that is not set. If that is not set, def
   */
  public String getFieldParam(String field, String param, String def) {
    String val = get(fpname(field, param));
    return val != null ? val : get(param, def);
  }

  /**
   * returns the String values of the field parameter, "f.field.param", or the values for "param" if
   * that is not set.
   */
  public String[] getFieldParams(String field, String param) {
    String[] val = getParams(fpname(field, param));
    return val != null ? val : getParams(param);
  }

  /**
   * Returns the Boolean value of the param, or null if not set. Use this method only when you want
   * to be explicit about absence of a value (<code>null</code>) vs the default value <code>false
   * </code>.
   *
   * @see #getBool(String, boolean)
   * @see #getPrimitiveBool(String)
   */
  public Boolean getBool(String param) {
    String val = get(param);
    return val == null ? null : StrUtils.parseBool(val);
  }

  /** Returns the boolean value of the param, or <code>false</code> if not set */
  public boolean getPrimitiveBool(String param) {
    return getBool(param, false);
  }

  /** Returns the boolean value of the param, or def if not set */
  public boolean getBool(String param, boolean def) {
    String val = get(param);
    return val == null ? def : StrUtils.parseBool(val);
  }

  /**
   * Returns the Boolean value of the field param, or the value for param, or null if neither is
   * set. Use this method only when you want to be explicit about absence of a value (<code>null
   * </code>) vs the default value <code>false</code>.
   *
   * @see #getFieldBool(String, String, boolean)
   * @see #getPrimitiveFieldBool(String, String)
   */
  public Boolean getFieldBool(String field, String param) {
    String val = getFieldParam(field, param);
    return val == null ? null : StrUtils.parseBool(val);
  }

  /**
   * Returns the boolean value of the field param, or the value for param or the default value of
   * boolean - <code>false</code>
   */
  public boolean getPrimitiveFieldBool(String field, String param) {
    return getFieldBool(field, param, false);
  }

  /**
   * Returns the boolean value of the field param, or the value for param, or def if neither is set.
   */
  public boolean getFieldBool(String field, String param, boolean def) {
    String val = getFieldParam(field, param);
    return val == null ? def : StrUtils.parseBool(val);
  }

  /**
   * Returns the Integer value of the param, or null if not set Use this method only when you want
   * to be explicit about absence of a value (<code>null</code>) vs the default value for int - zero
   * (<code>0</code>).
   *
   * @see #getInt(String, int)
   * @see #getPrimitiveInt(String)
   */
  public Integer getInt(String param) {
    String val = get(param);
    try {
      return val == null ? null : Integer.valueOf(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** Returns int value of the param or default value for int - zero (<code>0</code>) if not set. */
  public int getPrimitiveInt(String param) {
    return getInt(param, 0);
  }

  /** Returns the int value of the param, or def if not set */
  public int getInt(String param, int def) {
    String val = get(param);
    try {
      return val == null ? def : Integer.parseInt(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Returns the Long value of the param, or null if not set Use this method only when you want to
   * be explicit about absence of a value (<code>null</code>) vs the default value zero (<code>0
   * </code>).
   *
   * @see #getLong(String, long)
   */
  public Long getLong(String param) {
    String val = get(param);
    try {
      return val == null ? null : Long.valueOf(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** Returns the long value of the param, or def if not set */
  public long getLong(String param, long def) {
    String val = get(param);
    try {
      return val == null ? def : Long.parseLong(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Use this method only when you want to be explicit about absence of a value (<code>null</code>)
   * vs the default value zero (<code>0</code>).
   *
   * @return The int value of the field param, or the value for param or <code>null</code> if
   *     neither is set.
   * @see #getFieldInt(String, String, int)
   */
  public Integer getFieldInt(String field, String param) {
    String val = getFieldParam(field, param);
    try {
      return val == null ? null : Integer.valueOf(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** Returns the int value of the field param, or the value for param, or def if neither is set. */
  public int getFieldInt(String field, String param, int def) {
    String val = getFieldParam(field, param);
    try {
      return val == null ? def : Integer.parseInt(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Returns the Float value of the param, or null if not set Use this method only when you want to
   * be explicit about absence of a value (<code>null</code>) vs the default value zero (<code>0.0f
   * </code>).
   *
   * @see #getFloat(String, float)
   */
  public Float getFloat(String param) {
    String val = get(param);
    try {
      return val == null ? null : Float.valueOf(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** Returns the float value of the param, or def if not set */
  public float getFloat(String param, float def) {
    String val = get(param);
    try {
      return val == null ? def : Float.parseFloat(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Returns the Float value of the param, or null if not set Use this method only when you want to
   * be explicit about absence of a value (<code>null</code>) vs the default value zero (<code>0.0d
   * </code>).
   *
   * @see #getDouble(String, double)
   */
  public Double getDouble(String param) {
    String val = get(param);
    try {
      return val == null ? null : Double.valueOf(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /** Returns the float value of the param, or def if not set */
  public double getDouble(String param, double def) {
    String val = get(param);
    try {
      return val == null ? def : Double.parseDouble(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Returns the float value of the field param. Use this method only when you want to be explicit
   * about absence of a value (<code>null</code>) vs the default value zero (<code>0.0f</code>).
   *
   * @see #getFieldFloat(String, String, float)
   * @see #getPrimitiveFieldFloat(String, String)
   */
  public Float getFieldFloat(String field, String param) {
    String val = getFieldParam(field, param);
    try {
      return val == null ? null : Float.valueOf(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Returns the float value of the field param or the value for param or the default value for
   * float - zero (<code>0.0f</code>)
   */
  public float getPrimitiveFieldFloat(String field, String param) {
    return getFieldFloat(field, param, 0.0f);
  }

  /**
   * Returns the float value of the field param, or the value for param, or def if neither is set.
   */
  public float getFieldFloat(String field, String param, float def) {
    String val = getFieldParam(field, param);
    try {
      return val == null ? def : Float.parseFloat(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Returns the float value of the field param. Use this method only when you want to be explicit
   * about absence of a value (<code>null</code>) vs the default value zero (<code>0.0d</code>).
   *
   * @see #getDouble(String, double)
   */
  public Double getFieldDouble(String field, String param) {
    String val = getFieldParam(field, param);
    try {
      return val == null ? null : Double.valueOf(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  /**
   * Returns the float value of the field param, or the value for param, or def if neither is set.
   */
  public double getFieldDouble(String field, String param, double def) {
    String val = getFieldParam(field, param);
    try {
      return val == null ? def : Double.parseDouble(val);
    } catch (Exception ex) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  public static SolrParams wrapDefaults(SolrParams params, SolrParams defaults) {
    if (params == null) return defaults;
    if (defaults == null) return params;
    return new DefaultSolrParams(params, defaults);
  }

  public static SolrParams wrapAppended(SolrParams params, SolrParams defaults) {
    if (params == null) return defaults;
    if (defaults == null) return params;
    return AppendedSolrParams.wrapAppended(params, defaults);
  }

  /**
   * Convert this to a NamedList of unique keys with either String or String[] values depending on
   * how many values there are for the parameter.
   *
   * @deprecated see {@link SimpleOrderedMap#SimpleOrderedMap(MapWriter)}
   */
  @Deprecated
  public NamedList<Object> toNamedList() {
    final SimpleOrderedMap<Object> result = new SimpleOrderedMap<>();

    for (Iterator<String> it = getParameterNamesIterator(); it.hasNext(); ) {
      final String name = it.next();
      final String[] values = getParams(name);
      if (values.length == 1) {
        result.add(name, values[0]);
      } else {
        // currently, no reason not to use the same array
        result.add(name, values);
      }
    }
    return result;
  }

  /**
   * Returns this SolrParams as a proper URL encoded string, starting with {@code "?"}, if not
   * empty.
   */
  public String toQueryString() {
    try {
      final String charset = StandardCharsets.UTF_8.name();
      final StringBuilder sb = new StringBuilder(128);
      boolean first = true;
      for (final Iterator<String> it = getParameterNamesIterator(); it.hasNext(); ) {
        final String name = it.next(), nameEnc = URLEncoder.encode(name, charset);
        for (String val : getParams(name)) {
          sb.append(first ? '?' : '&')
              .append(nameEnc)
              .append('=')
              .append(URLEncoder.encode(val, charset));
          first = false;
        }
      }
      return sb.toString();
    } catch (UnsupportedEncodingException e) {
      // impossible!
      throw new AssertionError(e);
    }
  }

  /**
   * Generates a local-params string of the form <code>{! name=value name2=value2}</code>,
   * Protecting (without any quoting or escaping) any values that start with <code>$</code> (param
   * references).
   */
  public String toLocalParamsString() {
    final StringBuilder sb = new StringBuilder(128);
    sb.append("{!");
    // TODO perhaps look for 'type' and add here?  but it doesn't matter.
    for (final Iterator<String> it = getParameterNamesIterator(); it.hasNext(); ) {
      final String name = it.next();
      for (String val : getParams(name)) {
        sb.append(' '); // do so even the first time; why not.
        sb.append(name); // no escaping for name; it must follow "Java Identifier" rules.
        sb.append('=');
        if (val.startsWith("$")) {
          // maintain literal param ref...
          sb.append(val);
        } else {
          sb.append(ClientUtils.encodeLocalParamVal(val));
        }
      }
    }
    sb.append('}');
    return sb.toString();
  }

  /**
   * Like {@link #toQueryString()}, but only replacing enough chars so that the URL may be
   * unambiguously pasted back into a browser. This method can be used to properly log query
   * parameters without making them unreadable.
   *
   * <p>Characters with a numeric value less than 32 are encoded. &amp;,=,%,+,space are encoded.
   */
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(128);
    boolean first = true;
    for (final Iterator<String> it = getParameterNamesIterator(); it.hasNext(); ) {
      final String name = it.next();
      for (String val : getParams(name)) {
        if (!first) sb.append('&');
        first = false;
        StrUtils.partialURLEncodeVal(sb, name);
        sb.append('=');
        StrUtils.partialURLEncodeVal(sb, val);
      }
    }
    return sb.toString();
  }

  /**
   * A SolrParams is equal to another if they have the same keys and values. The order of keys does
   * not matter.
   */
  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (!(obj instanceof SolrParams b)) return false;

    // iterating this params, see if other has the same values for each key
    int count = 0;
    for (Entry<String, String[]> thisEntry : this) {
      String name = thisEntry.getKey();
      if (!Arrays.equals(thisEntry.getValue(), b.getParams(name))) return false;
      count++;
    }
    // does other params have the same number of keys?  It might have more but not less.
    Iterator<String> bNames = b.getParameterNamesIterator();
    while (bNames.hasNext()) {
      bNames.next();
      count--;
      if (count < 0) return false;
    }
    assert count == 0;
    return true;
  }

  @Override
  public int hashCode() {
    throw new UnsupportedOperationException();
  }

  /** An empty, immutable SolrParams. */
  public static SolrParams of() {
    return EmptySolrParams.INSTANCE;
  }

  /** An immutable SolrParams holding one pair (not null). */
  public static SolrParams of(String k, String v) {
    return new MapSolrParams(Map.of(k, v));
  }
}

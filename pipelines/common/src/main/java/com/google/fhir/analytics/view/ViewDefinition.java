/*
 * Copyright 2020-2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.analytics.view;

import ca.uhn.fhir.context.FhirVersionEnum;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Generate this class from StructureDefinition using tools like:
//  https://github.com/hapifhir/org.hl7.fhir.core/tree/master/org.hl7.fhir.core.generator
public class ViewDefinition {
  private static final Logger log = LoggerFactory.getLogger(ViewDefinition.class);
  private static Pattern CONSTANT_PATTERN = Pattern.compile("%[A-Za-z][A-Za-z0-9_]*");
  private static Pattern SQL_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

  @Getter @Nullable private String name;
  @Getter @Nullable private String resource;
  @Getter @Nullable private List<String> fhirVersion;
  @Getter @Nullable private List<Select> select;
  @Getter @Nullable private List<Where> where;
  // We don't need to expose constants because we do the replacement as part of the setup.
  @Nullable private List<Constant> constant;
  // This is also used internally for processing constants and should not be exposed.
  private final Map<String, String> constMap = new HashMap<>();

  // We try to limit the schema generation and validation to a minimum here as we prefer this to be
  // a pure data-object. This class is instantiated only with factory methods, so it is probably
  // okay to keep the current pattern.
  @Getter
  private ImmutableMap<String, Column> allColumns; // Initialized once in `validateAndSetUp`.

  // This class should only be instantiated with the `create*` factory methods.
  private ViewDefinition() {}

  public static ViewDefinition createFromFile(Path jsonFile)
      throws IOException, ViewDefinitionException {
    Gson gson = new Gson();
    try (Reader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
      ViewDefinition view = gson.fromJson(reader, ViewDefinition.class);
      view.validateAndSetUp(true, null);
      return view;
    }
  }

  public static ViewDefinition createFromString(String jsonContent) throws ViewDefinitionException {
    Gson gson = new Gson();
    try {
      ViewDefinition view = gson.fromJson(jsonContent, ViewDefinition.class);
      if (view == null) {
        throw new ViewDefinitionException("Error in parsing ViewDefinition JSON content!");
      }
      view.validateAndSetUp(true, null);
      return view;
    } catch (JsonSyntaxException e) {
      log.error("Error in parsing ViewDefinition JSON: ", e);
      throw new ViewDefinitionException(e.getMessage());
    }
  }

  /**
   * This does two main tasks: 1) replacing constants in all FHIRPaths, 2) collecting the list of
   * column with their types and checking for inconsistencies.
   *
   * @param checkName whether to check name or not; this should always be true in production code.
   * @throws ViewDefinitionException if there is any column inconsistency, e.g., duplicates.
   */
  @VisibleForTesting
  void validateAndSetUp(boolean checkName, @Nullable String fhirVersion)
      throws ViewDefinitionException {
    if (Strings.isNullOrEmpty(resource)) {
      throw new ViewDefinitionException(
          "The resource field of a view should be a valid FHIR resource type.");
    }
    if (fhirVersion != null) {
      this.fhirVersion = List.of(fhirVersion);
    }
    if (checkName
        && (Strings.isNullOrEmpty(this.name) || !SQL_NAME_PATTERN.matcher(this.name).matches())) {
      throw new ViewDefinitionException("The name is not a valid 'sql-name': " + name);
    }
    if (constant != null) {
      for (Constant c : constant) {
        if (!SQL_NAME_PATTERN.matcher(c.name).matches()) {
          throw new ViewDefinitionException(
              "Constant name " + c.name + " does not match 'sql-name' pattern!");
        }
        constMap.put(c.getName(), c.convertValueToString());
      }
    }
    // We do the string replacements recursively here when constructing a ViewDefinition, such that
    // applying the FHIRPaths to many resources later on, does not need constant replacement.
    if (where != null) {
      for (Where w : where) {
        if (w.getPath() == null) {
          throw new ViewDefinitionException("The `path` of `where` cannot be null!");
        }
        w.path = validateAndReplaceConstants(w.getPath());
      }
    }
    allColumns = ImmutableMap.copyOf(validateAndReplaceConstantsInSelects(select, newTypeMap()));
  }

  /**
   * @param selects the list of Select structures to be validated; the constant replacement happens
   *     in-place, i.e., inside Select structures.
   * @param currentColumns the set of column names already found in the parent view.
   * @return the [ordered] map of new column names and their types as string.
   * @throws ViewDefinitionException for repeated columns or other requirements not satisfied.
   */
  private LinkedHashMap<String, Column> validateAndReplaceConstantsInSelects(
      @Nullable List<Select> selects, LinkedHashMap<String, Column> currentColumns)
      throws ViewDefinitionException {
    LinkedHashMap<String, Column> newCols = newTypeMap();
    if (selects == null) {
      return newCols;
    }
    for (Select s : selects) {
      newCols.putAll(
          validateAndReplaceConstantsInOneSelect(s, unionTypeMaps(currentColumns, newCols)));
    }
    return newCols;
  }

  private static LinkedHashMap<String, Column> newTypeMap() {
    return new LinkedHashMap<>();
  }

  private static LinkedHashMap<String, Column> unionTypeMaps(
      LinkedHashMap<String, Column> m1, LinkedHashMap<String, Column> m2) {
    LinkedHashMap<String, Column> u = new LinkedHashMap<>();
    u.putAll(m1);
    u.putAll(m2);
    return u;
  }

  private LinkedHashMap<String, Column> validateAndReplaceConstantsInOneSelect(
      Select select, LinkedHashMap<String, Column> currentColumns) throws ViewDefinitionException {
    LinkedHashMap<String, Column> newCols = newTypeMap();
    if (select.getColumn() != null) {
      for (Column c : select.getColumn()) {
        String colName = Strings.nullToEmpty(c.name);
        if (colName.isEmpty()) {
          throw new ViewDefinitionException("Column name cannot be empty!");
        }
        if (!SQL_NAME_PATTERN.matcher(colName).matches()) {
          throw new ViewDefinitionException(
              "Column name " + colName + " does not match 'sql-name' pattern!");
        }
        if (Strings.nullToEmpty(c.path).isEmpty()) {
          throw new ViewDefinitionException("Column path cannot be empty for " + c.name);
        }
        if (currentColumns.containsKey(colName) || newCols.containsKey(colName)) {
          throw new ViewDefinitionException("Repeated column name " + colName);
        }
        // TODO implement automatic type derivation support.
        newCols.put(colName, c);
        c.path = validateAndReplaceConstants(c.getPath());
      }
    }
    if (!Strings.nullToEmpty(select.getForEach()).isEmpty()) {
      select.forEach = validateAndReplaceConstants(select.getForEach());
    }
    if (!Strings.nullToEmpty(select.getForEachOrNull()).isEmpty()) {
      select.forEachOrNull = validateAndReplaceConstants(select.getForEachOrNull());
    }
    newCols.putAll(
        validateAndReplaceConstantsInSelects(
            select.getSelect(), unionTypeMaps(currentColumns, newCols)));
    LinkedHashMap<String, Column> unionCols = null;
    if (select.getUnionAll() != null) {
      for (Select u : select.getUnionAll()) {
        LinkedHashMap<String, Column> uCols =
            validateAndReplaceConstantsInOneSelect(u, unionTypeMaps(currentColumns, newCols));
        if (unionCols == null) {
          unionCols = uCols;
        } else {
          if (!compatibleColumns(unionCols, uCols)) {
            throw new ViewDefinitionException(
                "Union columns are not consistent "
                    + Arrays.toString(uCols.entrySet().toArray())
                    + " vs "
                    + Arrays.toString(unionCols.entrySet().toArray()));
          }
        }
      }
    }
    if (unionCols != null) {
      return unionTypeMaps(newCols, unionCols);
    }
    return newCols;
  }

  private boolean compatibleColumns(Map<String, Column> cols1, Map<String, Column> cols2) {
    Preconditions.checkNotNull(cols1);
    Preconditions.checkNotNull(cols2);
    if (cols1.size() != cols2.size()) {
      return false;
    }
    Iterator<Entry<String, Column>> cols2Iter = cols2.entrySet().iterator();
    for (Entry<String, Column> e1 : cols1.entrySet()) {
      Entry<String, Column> e2 = cols2Iter.next();
      if (!e2.getKey().equals(e1.getKey())) {
        return false;
      }
      // We only check column name, type, collection and ignore other fields, e.g., description.
      String t1 = Strings.nullToEmpty(e1.getValue().getType());
      String t2 = Strings.nullToEmpty(e2.getValue().getType());
      if (!t1.equals(t2)) {
        return false;
      }
      if (e1.getValue().isCollection() != e2.getValue().isCollection()) {
        return false;
      }
    }
    return true;
  }

  @Nullable
  private String validateAndReplaceConstants(@Nullable String fhirPath)
      throws ViewDefinitionException {
    if (fhirPath == null) {
      return null;
    }
    Matcher matcher = CONSTANT_PATTERN.matcher(fhirPath);
    try {
      return matcher.replaceAll(
          m -> {
            String constName = m.group().substring(1); // drops the initial '%'.
            if (!constMap.containsKey(constName)) {
              // We throw an unchecked exception here because it is inside the lambda function.
              throw new IllegalArgumentException("Constant not defined: " + constName);
            }
            return constMap.get(constName);
          });
    } catch (IllegalArgumentException e) {
      // Here we catch that exception and throw the right checked exception.
      throw new ViewDefinitionException(e.getMessage());
    }
  }

  @Getter
  public static class Select {
    @Nullable private List<Column> column;
    @Nullable private List<Select> select;
    @Nullable private String forEach;
    @Nullable private String forEachOrNull;
    @Nullable private List<Select> unionAll;
  }

  @Builder(toBuilder = true)
  @Getter
  public static class Column {
    @Nullable private String path;
    @Nullable private String name;
    @Nullable private String type;
    @Nullable private boolean collection;
    @Nullable private String description;
    // The following fields are _not_ read from the ViewDefinition.
    @Nullable private String inferredType;
    private boolean inferredCollection;
  }

  @Getter
  public static class Where {
    @Nullable private String path;
  }

  @Getter
  public static class Constant {
    private String name = "!BAD_NAME!"; // This has to be set in the input ViewDefinition.
    @Nullable private String valueBase64Binary;
    @Nullable private Boolean valueBoolean;
    @Nullable private String valueCanonical;
    @Nullable private String valueCode;
    @Nullable private String valueDate;
    @Nullable private String valueDateTime;
    @Nullable private String valueDecimal;
    @Nullable private String valueId;
    @Nullable private String valueInstant;
    @Nullable private Integer valueInteger;
    @Nullable private Integer valueInteger64;
    @Nullable private String valueOid;
    @Nullable private String valueString;
    @Nullable private Integer valuePositiveInt;
    @Nullable private String valueTime;
    @Nullable private Integer valueUnsignedInt;
    @Nullable private String valueUri;
    @Nullable private String valueUrl;
    @Nullable private String valueUuid;

    private String quoteString(String s) {
      return "'" + s + "'";
    }

    /**
     * @return a string that can replace this constant in FHIRPaths.
     * @throws ViewDefinitionException if zero or more than one value is defined.
     */
    public String convertValueToString() throws ViewDefinitionException {
      int c = 0;
      String stringValue = null;
      if (null != valueBase64Binary) {
        stringValue = quoteString(valueBase64Binary);
        c++;
      }
      if (null != valueCanonical) {
        stringValue = quoteString(valueCanonical);
        c++;
      }
      if (null != valueCode) {
        stringValue = quoteString(valueCode);
        c++;
      }
      if (null != valueDate) {
        stringValue = "@" + valueDate;
        c++;
      }
      if (null != valueDateTime) {
        stringValue = "@" + valueDateTime;
        c++;
      }
      if (null != valueDecimal) {
        stringValue = valueDecimal;
        c++;
      }
      if (null != valueId) {
        stringValue = quoteString(valueId);
        c++;
      }
      if (null != valueInstant) {
        stringValue = quoteString(valueInstant);
        c++;
      }
      if (null != valueOid) {
        stringValue = quoteString(valueOid);
        c++;
      }
      if (null != valueString) {
        stringValue = quoteString(valueString);
        c++;
      }
      if (null != valueTime) {
        stringValue = "@" + valueTime;
        c++;
      }
      if (null != valueUri) {
        stringValue = quoteString(valueUri);
        c++;
      }
      if (null != valueUrl) {
        stringValue = quoteString(valueUrl);
        c++;
      }
      if (null != valueUuid) {
        stringValue = quoteString(valueUuid);
        c++;
      }
      if (null != valueBoolean) {
        stringValue = valueBoolean.toString();
        c++;
      }
      if (null != valueUnsignedInt) {
        stringValue = valueUnsignedInt.toString();
        c++;
      }
      if (null != valuePositiveInt) {
        stringValue = valuePositiveInt.toString();
        c++;
      }
      if (null != valueInteger) {
        stringValue = valueInteger.toString();
        c++;
      }
      if (null != valueInteger64) {
        stringValue = valueInteger64.toString();
        c++;
      }
      if (stringValue == null) {
        throw new ViewDefinitionException("None of the value[x] elements are set!");
      }
      if (c > 1) {
        throw new ViewDefinitionException(
            "Exactly one the value[x] elements should be set; got " + c);
      }
      return stringValue;
    }
  }

  /** Coverts the given FHIR version string to a {@link FhirVersionEnum}. */
  public static FhirVersionEnum convertFhirVersion(String fhirVersion) {
    switch (fhirVersion.substring(0, 3)) {
      case "3.0":
        return FhirVersionEnum.DSTU3;
      case "4.0":
        return FhirVersionEnum.R4;
      case "4.3":
        return FhirVersionEnum.R4B;
      case "5.0":
        return FhirVersionEnum.R5;
      default:
        throw new IllegalArgumentException("FHIR version not supported!");
    }
  }
}

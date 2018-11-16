/*
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

package app.metatron.discovery.domain.dataprep.teddy;

import app.metatron.discovery.common.GlobalObjectMapper;
import app.metatron.discovery.domain.dataprep.exceptions.PrepErrorCodes;
import app.metatron.discovery.domain.dataprep.exceptions.PrepException;
import app.metatron.discovery.domain.dataprep.exceptions.PrepMessageKey;
import app.metatron.discovery.domain.dataprep.teddy.exceptions.CannotSerializeIntoJsonException;
import app.metatron.discovery.prep.parser.preparation.RuleVisitorParser;
import app.metatron.discovery.prep.parser.preparation.rule.Header;
import app.metatron.discovery.prep.parser.preparation.rule.Keep;
import app.metatron.discovery.prep.parser.preparation.rule.Rename;
import app.metatron.discovery.prep.parser.preparation.rule.Rule;
import app.metatron.discovery.prep.parser.preparation.rule.expr.Expr;
import app.metatron.discovery.prep.parser.preparation.rule.expr.Expression;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.joda.time.DateTime;
import org.joda.time.LocalDateTime;

import javax.persistence.criteria.CriteriaBuilder;
import java.io.*;
import java.sql.Timestamp;
import java.util.*;

import static app.metatron.discovery.domain.dataprep.PrepProperties.HADOOP_CONF_DIR;

public class Util {
  private  static RuleVisitorParser ruleVisitorParser = null;

  public static List<String[]> loadGridLocalCsv(String targetUrl, String delimiter, int limitRowCnt, Configuration conf, List<String> colNames) {

    List<String[]> grid = new ArrayList<>();

    BufferedReader br = null;
    String line;
    String quoteSymbol="\"";
    try {
      InputStreamReader inputStreamReader;
      if(true==targetUrl.toLowerCase().startsWith("hdfs://")) {   // The primary store is HDFS, even for LOCALLY uploaded files.
        // Once stored on HDFS, then you should be connected on, to find it.
        if (conf == null) {
          throw PrepException.create(PrepErrorCodes.PREP_INVALID_CONFIG_CODE,
                  PrepMessageKey.MSG_DP_ALERT_REQUIRED_PROPERTY_MISSING, HADOOP_CONF_DIR);
        }
        Path pt=new Path(targetUrl);
        FileSystem fs = FileSystem.get(conf);
        FSDataInputStream fsDataInputStream = fs.open(pt);
        inputStreamReader = new InputStreamReader(fsDataInputStream);
      } else {
        File theFile = new File(targetUrl);
        FileInputStream fileInputStream = new FileInputStream(theFile);
        inputStreamReader = new InputStreamReader(fileInputStream);
      }
      br = new BufferedReader(inputStreamReader);

      // get colNames
      while ((line = br.readLine()) != null) {
        if(colNames!=null && colNames.size()==0) {
          colNames.addAll(Arrays.asList(csvLineSplitter(line, delimiter, quoteSymbol)));
          continue;
        }

        String[] strCols = csvLineSplitter(line, delimiter, quoteSymbol);
        grid.add(strCols);
        if (grid.size() == limitRowCnt) {
          break;
        }
      }
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (br != null) {
        try {
          br.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return grid;
  }

  /*
    * Split rules used when creating datasets from csv files.
    * 1. Split line by a delimiter.
    * 2. Delimiters inside quotes are ignored.
    * 3. Only one pair of quotes per column is valid.
    *    > Delimiter that appears after the 3rd quotation mark will not be ignored.
    * 4. If there are only two quotation marks, and they enclose the entire word in the column, remove the quotation marks
     */
  public static String[] csvLineSplitter(String line, String delimiter, String quote) {
    List<String> result = new ArrayList<>();

    char[] lineChar = line.toCharArray();
    char delimiterChar = delimiter.charAt(0);
    char quoteChar = quote.charAt(0);

    List<Integer> listDelimiter = Lists.newArrayList();
    boolean openQuote = false;
    int delimiterLength = delimiter.length();
    int quoteLength = quote.length();
    int lineLength = line.length();
    for(int i=0; i<lineLength; i++) {
      if(i+quoteLength<=lineLength && quote.equals(line.substring(i,i+quoteLength))) {
        openQuote = !openQuote;
      }
      if(!openQuote && i+delimiterLength<=lineLength && delimiter.equals(line.substring(i,i+delimiterLength))) {
        listDelimiter.add(i);
      }
    }
    int start = 0;
    for(Integer delimiterIdx : listDelimiter) {
      String column = line.substring(start,delimiterIdx);
      start = delimiterIdx + delimiterLength;
      if( !column.equals(quote) && column.startsWith(quote) && column.endsWith(quote) ) {
        column = column.substring(quoteLength,column.length()-quoteLength);
      }
      if( column.contains(quote) ) {
        column = column.replaceAll(quote.concat(quote),quote);
      }
      result.add(column);
    }
    if(start<lineLength) {
      String column = line.substring(start);
      if (!column.equals(quote) && column.startsWith(quote) && column.endsWith(quote)) {
        column = column.substring(quoteLength, column.length() - quoteLength);
      }
      if (column.contains(quote)) {
        column = column.replaceAll(quote.concat(quote), quote);
      }
      result.add(column);
    }
    return result.toArray(new String[result.size()]);
  }

  public static int getLengthUTF8(CharSequence sequence) {
    if (sequence == null) {
      return 0;
    }
    int count = 0;
    for (int i = 0, len = sequence.length(); i < len; i++) {
      char ch = sequence.charAt(i);
      if (ch <= 0x7F) {
        count++;
      } else if (ch <= 0x7FF) {
        count += 2;
      } else if (Character.isHighSurrogate(ch)) {
        count += 4;
        ++i;
      } else {
        count += 3;
      }
    }
    return count;
  }

  public final static double EPSILON = 1.0e-20;

  public static Double round(double val) {
    return Double.valueOf(String.format("%.16f", Math.abs(val) < EPSILON ? 0.0 : val));
  }

  static void showSep(List<Integer> widths) {
    System.out.print("+");
    for (int width : widths) {
      for (int i = 0; i < width; i++) {
        System.out.print("-");
      }
      System.out.print("+");
    }
    System.out.println("");
  }

  static void showColNames(List<Integer> widths, List<String> colNames) {
    assert widths.size() == colNames.size() :
            String.format("widths.size()=%d colNames.size()=%d", widths.size(), colNames);

    System.out.print("|");
    for (int i = 0; i < widths.size(); i++) {
      System.out.print(String.format("%" + widths.get(i) + "s", colNames.get(i)));
      System.out.print("|");
    }
    System.out.println("");
  }

  static void showColTypes(List<Integer> widths, List<ColumnDescription> colDescs) {
    assert widths.size() == colDescs.size() :
            String.format("widths.size()=%d colDescs.size()=%d", widths.size(), colDescs);

    System.out.print("|");
    for (int i = 0; i < widths.size(); i++) {
      System.out.print(String.format("%" + widths.get(i) + "s", colDescs.get(i).getType()));
      System.out.print("|");
    }
    System.out.println("");
  }

  static void showRow(List<Integer> widths, Row row) {
    System.out.print("|");
    for (int i = 0; i < widths.size(); i++) {
      System.out.print(String.format(
              "%" + widths.get(i) + "s", row.get(i) == null ? "null" : row.get(i).toString()));
      System.out.print("|");
    }
    System.out.println("");
  }

  public static String getJsonRuleString(String ruleString) throws CannotSerializeIntoJsonException {
    String jsonRuleString;

    if (ruleString.startsWith(CREATE_RULE_PREFIX)) {
      return Util.getCreateJsonRuleString(ruleString);
    }

    if (ruleVisitorParser == null) {
      ruleVisitorParser = new RuleVisitorParser();
    }

    Rule rule = ruleVisitorParser.parse(ruleString);

    try {
      jsonRuleString = GlobalObjectMapper.getDefaultMapper().writeValueAsString(rule);
    } catch (JsonProcessingException e) {
      throw new CannotSerializeIntoJsonException(e.getMessage());
    }

    return jsonRuleString;
  }

//  public static String argsToString(List<Object> args) {
//    String result = "";
//
//    for (Object arg : args) {
//      result += nodeToString((Map) arg) + ", ";
//    }
//
//    if (result.length() < 2) {
//      return result;
//    }
//
//    return result.substring(0, result.length() - 2);
//  }

  private static String nodeToString(Object node) {
    if (node instanceof Map) {
      Map map = (Map) node;
      Object op = map.get("op");
      if (op != null) {
        return String.format("%s %s %s", nodeToString(map.get("left")), op, nodeToString(map.get("right")));
      }

      Object name = map.get("name");
      if (name != null) {
        assert map.containsKey("args");
        return String.format("%s(%s)", name, nodeToString(map.get("args")));
      }

      return map.get("value").toString();
    }

    if (node instanceof List) {
      List list = (List) node;
      String result = "";

      for (Object elem : list) {
        result += nodeToString(elem) + ", ";
      }

      return result.length() < 2 ? result : result.substring(0, result.length() - 2);
    }

    return node.toString();
  }

  private static String shortenColumnList(Object node) {
    if (node instanceof List) {
      List<Object> cols = (List<Object>) node;
      if (cols.size() >= 3) {
        return cols.size() + " columns";
      }
    }

    return nodeToString(node);
  }

  private static String combineCountAndUnit(int count, String unit) {
    assert count > 0 : unit;

    if (count == 1) {
      return String.format("%d %s", count, unit);
    }
    return String.format("%d %ss", count, unit);
  }

//  private static String getDsNameList(Object dataset2) {
//    if (dataset2 instanceof List) {
//      List<Object> dsIds = (List<Object>) dataset2;
//      if (dsIds.size() >= 3) {
//        return dsIds.size() + " columns";
//      }
//    }
//
//    return nodeToString(node);
//  }

  static final String FMTSTR_CREATE       = "create with: %s";                  // with
  static final String FMTSTR_HEADER       = "Convert row %d to header";         // rownum
  static final String FMTSTR_KEEP         = "Keep rows where %s";               // row
  static final String FMTSTR_RENAME       = "Rename %s to %s";                  // col, to
  static final String FMTSTR_RENAMES      = "Rename %s";                        // col
  static final String FMTSTR_NEST         = "Convert %s into %s";               // col, into
  static final String FMTSTR_UNNEST       = "Create a new column from %s";      // col
  static final String FMTSTR_SETTYPE      = "set type %s to %s";                // col, type
  static final String FMTSTR_SETFORMAT    = "set format %s to %s";              // col, format
  static final String FMTSTR_DERIVE       = "Create %s from %s";                // as, value
  static final String FMTSTR_DELETE       = "Delete rows where %s";             // row
  static final String FMTSTR_SET          = "Set %s to %s";                     // col, value
  static final String FMTSTR_SPLIT        = "Split %s into %s on %s";           // col, limit, on
  static final String FMTSTR_EXTRACT      = "Extract %s %s from %s";            // on, limit, col
  static final String FMTSTR_FLATTEN      = "Convert arrays in %s to rows";     // col
  static final String FMTSTR_COUNTPATTERN = "Count occurrences of %s in %s";    // value, col
  static final String FMTSTR_SORT         = "Sort rows by %s %s";               // order, type
  static final String FMTSTR_REPLACE      = "Replace %s from %s with %s";       // on, col, with
  static final String FMTSTR_REPLACES     = "Replace %s";                       // col
  static final String FMTSTR_MERGE        = "Concatenate %s separated by %s";   // col, with
  static final String FMTSTR_AGGREGATE    = "Aggregate with %s grouped by %s";  // value, group
  static final String FMTSTR_MOVE         = "Move %s %s";                       // col, before/after
  static final String FMTSTR_JOIN_UNION   = "%s with %s";                       // command, strDsNames

//      case "move":
//        shortRuleString = "Move ${column}";
//      shortRuleString += "${rule.before ? " before " + rule.before : " after " + rule.after }";
//      break;


  public static String getShortRuleString(String jsonRuleString) {
    Map<String, Object> mapRule = null;
    Object col, to, on, val, order, type, with, group;
    int limit;
    String before, after;
    String strCount;

    try {
      mapRule = (Map<String, Object>) GlobalObjectMapper.getDefaultMapper().readValue(jsonRuleString, Map.class);
    } catch (IOException e) {
      e.printStackTrace();
      // suppressed. this is impossible because the input is just from a JSON writer.
    }

    String ruleCommand = (String) mapRule.get("name");
    String shortRuleString;

    switch (ruleCommand) {
      case "create":
        shortRuleString = String.format(FMTSTR_CREATE, mapRule.get("with"));
        break;
      case "header":
        shortRuleString = String.format(FMTSTR_HEADER, mapRule.get("rownum"));
        break;
      case "keep":
        shortRuleString = String.format(FMTSTR_KEEP, nodeToString(mapRule.get("row")));
        break;
      case "rename":
        col = ((Map) mapRule.get("col")).get("value");
        to = ((Map) mapRule.get("to")).get("value");

        if (col instanceof List && ((List<Object>) col).size() >= 3) {
          shortRuleString = String.format(FMTSTR_RENAMES, shortenColumnList(col));
        } else {
          shortRuleString = String.format(FMTSTR_RENAME, nodeToString(col), nodeToString(to));
        }
        break;
      case "nest":
        col = ((Map) mapRule.get("col")).get("value");
        shortRuleString = String.format(FMTSTR_NEST, nodeToString(col), mapRule.get("into"));
        break;
      case "unnest":
        shortRuleString = String.format(FMTSTR_UNNEST, mapRule.get("col"));
        break;
      case "settype":
        col = ((Map) mapRule.get("col")).get("value");
        shortRuleString = String.format(FMTSTR_SETTYPE, shortenColumnList(col), mapRule.get("type"));
        break;
      case "setformat":
        col = ((Map) mapRule.get("col")).get("value");
        shortRuleString = String.format(FMTSTR_SETFORMAT, shortenColumnList(col), mapRule.get("format"));
        break;
      case "derive":
        shortRuleString = String.format(FMTSTR_DERIVE, mapRule.get("as"), nodeToString(mapRule.get("value")));
        break;
      case "delete":
        shortRuleString = String.format(FMTSTR_DELETE, nodeToString(mapRule.get("row")));
        break;
      case "set":
        col = ((Map) mapRule.get("col")).get("value");
        val = mapRule.get("value");
        shortRuleString = String.format(FMTSTR_SET, shortenColumnList(col), nodeToString(val));
        break;
      case "split":
        on = ((Map) mapRule.get("on")).get("value");
        limit = ((Integer) (mapRule.get("limit"))).intValue();
        strCount = combineCountAndUnit(limit + 1, "column");      // split N times, produces N + 1 columns
        shortRuleString = String.format(FMTSTR_SPLIT, mapRule.get("col"), strCount, nodeToString(on));
        break;
      case "extract":
        on = ((Map) mapRule.get("on")).get("value");
        limit = ((Integer) (mapRule.get("limit"))).intValue();
        strCount = combineCountAndUnit(limit, "time");            // extract N times, produces just N columns
        shortRuleString = String.format(FMTSTR_EXTRACT, nodeToString(on), strCount, mapRule.get("col"));
        break;
      case "flatten":
        shortRuleString = String.format(FMTSTR_FLATTEN, mapRule.get("col"));
        break;
      case "countpattern":
        col = ((Map) mapRule.get("col")).get("value");
        on = ((Map) mapRule.get("on")).get("value");
        shortRuleString = String.format(FMTSTR_COUNTPATTERN, nodeToString(on), shortenColumnList(col));
        break;
      case "sort":
        order = ((Map) mapRule.get("order")).get("value");
        String sortType = "ASC";
        type = mapRule.get("type");
        if (type != null) {
          if ((((Map) type).get("escapedValue")).toString().equalsIgnoreCase("DESC")) {
            sortType = "DESC";
          }
        }
        shortRuleString = String.format(FMTSTR_SORT, nodeToString(order), sortType);
        break;
      case "replace":
        on = ((Map) mapRule.get("on")).get("value");
        col = ((Map) mapRule.get("col")).get("value");
        with = ((Map) mapRule.get("with")).get("value");

        if (col instanceof List && ((List<Object>) col).size() >= 3) {
          shortRuleString = String.format(FMTSTR_REPLACES, shortenColumnList(col));
        } else {
          shortRuleString = String.format(FMTSTR_REPLACE, nodeToString(on), shortenColumnList(col), nodeToString(with));
        }
        break;
      case "merge":
        col = ((Map) mapRule.get("col")).get("value");
        shortRuleString = String.format(FMTSTR_MERGE, nodeToString(col), mapRule.get("with"));
        break;
      case "aggregate":
        val = mapRule.get("value");
        group = ((Map) mapRule.get("group")).get("value");
        shortRuleString = String.format(FMTSTR_AGGREGATE, nodeToString(val), shortenColumnList(group));
        break;
      case "move":
        col = ((Map) mapRule.get("col")).get("value");
        before = (String) mapRule.get("before");
        after = (String) mapRule.get("after");
        String strTo = (before != null) ? "before " + before : "after " + after;
        shortRuleString = String.format(FMTSTR_MOVE, shortenColumnList(col), strTo);
        break;
//      case "Union":
//      case "Join":
//        String strDsNames = getDsNameList(((Map) mapRule.get("dataset2")).get("value"));
//        shortRuleString = String.format(FMTSTR_JOIN_UNION, ruleCommand, strDsNames);
//        break;

      default:
        shortRuleString = ruleCommand + " unknown";
    }

    return shortRuleString;

//      case "merge":
//        shortRuleString = "Concatenate ${column} separated by ${rule.with}";
//      break;
//      case "aggregate":
//        shortRuleString = "Aggregate with ${rule.value.escapedValue ? rule.value.escapedValue : rule.value.value.length + " functions"} grouped by ";
//      if ("string" === typeof rule.group.value) {
//        shortRuleString += "${rule.group.value}"
//      } else if (rule.group.value.length === 2) {
//        shortRuleString += "${rule.group.value.join(", ")}"
//      } else {
//        shortRuleString += "${rule.group.value.length} columns"
//      }
//      break;
//      case "move":
//        shortRuleString = "Move ${column}";
//      shortRuleString += "${rule.before ? " before " + rule.before : " after " + rule.after }";
//      break;
//      case "Union":
//      case "Join":
//        shortRuleString = "${rule.command} with ";
//
//      let datasetIds = [];
//      if (rule.dataset2.escapedValue) {
//        datasetIds = [rule.dataset2.escapedValue]
//      } else {
//        rule.dataset2.value.forEach((item) => {
//                datasetIds.push(item.substring(1, item.length - 1))
//        })
//      }
//
//      if (datasetIds.length === 1) {
//        shortRuleString += "${this.selectedDataSet.gridResponse.slaveDsNameMap[datasetIds[0]]}";
//      } else if (datasetIds.length === 2) {
//        shortRuleString += "${this.selectedDataSet.gridResponse.slaveDsNameMap[datasetIds[0]]}, ${this.selectedDataSet.gridResponse.slaveDsNameMap[datasetIds[1]]}";
//      } else {
//        shortRuleString += "${datasetIds.length} datasets";
//      }
//
//      break;
//      case "derive":
//        let deriveCondition = ruleString.split("value: ");
//        deriveCondition = deriveCondition[1].split(" as: ");
//        shortRuleString = "Create ${rule.as} from ${deriveCondition[0]}";
//      break;
//      case "pivot":
//        let formula = "";
//        if (rule.value.escapedValue) {
//          formula = rule.value.escapedValue
//        } else {
//          let list = [];
//          rule.value.value.forEach((item) => {
//                  list.push(item.substring(1, item.length - 1));
//          });
//          formula = list.toString();
//        }
//        shortRuleString = "Pivot ${column} and compute ${formula} grouped by";
//
//      if ("string" === typeof rule.group.value || rule.group.value.length === 2) {
//        shortRuleString += " ${rule.group.value}";
//      } else {
//        shortRuleString += " ${rule.group.value.length} columns";
//      }
//      break;
//      case "unpivot":
//        shortRuleString = "Convert ";
//        if ("string" === typeof rule.col.value) {
//        shortRuleString += "${rule.col.value} into row";
//      } else if (rule.col.value.length > 1) {
//        shortRuleString += "${column} into rows";
//      }
//      break;
//      case "drop":
//        shortRuleString = "Drop ${column}";
//      break;
//      default:
//        shortRuleString = "";
//        break;
//
//    }
//
//    return jsonRuleString;
  }

  private static final String CREATE_RULE_PREFIX = "create with: ";

  public static String getCreateRuleString(String dsId) {
    return CREATE_RULE_PREFIX + dsId;
  }

  public static String getUpstreamDsId(String ruleString) {
    return ruleString.substring(CREATE_RULE_PREFIX.length());
  }

  public static String getCreateJsonRuleString(String ruleString) {
    Map<String, Object> map = new HashMap();
    String jsonRuleString = null;

    map.put("name", "create");
    map.put("with", ruleString.substring(CREATE_RULE_PREFIX.length()));
    try {
      jsonRuleString = GlobalObjectMapper.getDefaultMapper().writeValueAsString(map);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }

    return jsonRuleString;
  }

  public static Date jodatToSQLDate(LocalDateTime localDateTime) {
    return new Date(localDateTime.toDateTime().getMillis());
  }

  public static Timestamp jodaToSQLTimestamp(LocalDateTime localDateTime) {
    return new Timestamp(localDateTime.toDateTime().getMillis());
  }

  public static LocalDateTime sqlTimestampToJodaLocalDateTime(Timestamp timestamp) {
    return LocalDateTime.fromDateFields(timestamp);
  }

  public static DateTime sqlTimestampToJodaDateTime(Timestamp timestamp) {
    return sqlTimestampToJodaLocalDateTime(timestamp).toDateTime();
  }

}

/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.contrib.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC output adapter operator, for HashMap column mapping and non-transaction type database write. Key is string, Value can be any type derived from Java object.<p><br>
 * <br>
 * Ports:<br>
 * <b>Input</b>: This has a single input port that writes data into database.<br>
 * <b>Output</b>: No output port<br>
 * <br>
 * Properties:<br>
 * None<br>
 * <br>
 * Compile time checks:<br>
 * None<br>
 * <br>
 * Run time checks:<br>
 * None <br>
 * <br>
 * Benchmarks:<br>
 * TBD<br>
 * <br>
 *
 * @author Locknath Shil <locknath@malhar-inc.com>
 */
public class JDBCHashMapNonTransactionOutputOperator<V> extends JDBCNonTransactionOutputOperator<HashMap<String, V>>
{
  private static final Logger logger = LoggerFactory.getLogger(JDBCHashMapNonTransactionOutputOperator.class);
  private static final int propIdx = 0;
  private static final int colIdx = 1;
  private static final int typeIdx = 2;

  /**
   * The column mapping will have following pattern: <br>
   * Property:[Table.]Column:Type <br>
   * Followings are two examples: <br>
   * prop1:t1.col1:INTEGER,prop2:t3.col2:BIGINT,prop5:t3.col5:CHAR,prop6:t2.col4:DATE,prop7:t1.col7:DOUBLE,prop3:t2.col6:VARCHAR(10),prop4:t1.col3:DATE <br>
   * prop1:col1:INTEGER,prop2:col2:BIGINT,prop5:col5:CHAR,prop6:col4:DATE,prop7:col7:DOUBLE,prop3:col6:VARCHAR(10),prop4:col3:DATE <br>
   *
   * @param mapping
   */
  @Override
  protected void parseMapping(ArrayList<String> mapping)
  {
    int num = mapping.size();
    String table;
    String column;

    for (int idx = 0; idx < num; ++idx) {
      String[] fields = mapping.get(idx).split(FIELD_DELIMITER);
      if (fields.length != 3) {
        throw new RuntimeException("Incorrect column mapping for HashMap. Correct mapping should be \"Property:[Table.]Column:Type\"");
      }

      int colDelIdx = fields[colIdx].indexOf(COLUMN_DELIMITER);
      if (colDelIdx != -1) { // table name is used
        table = fields[colIdx].substring(0, colDelIdx);
        column = fields[colIdx].substring(colDelIdx + 1);
        if (!tableNames.contains(table)) {
          tableNames.add(table);
        }
      }
      else { // table name not used; so this must be single table
        table = getTableName();
        if (table.isEmpty()) {
          throw new RuntimeException("Table name can not be empty");
        }
        if (tableNames.isEmpty()) {
          tableNames.add(table);
        }
        column = fields[colIdx];
      }
      columnNames.add(column);
      keyToTable.put(fields[propIdx], table);

      if (tableToColumns.containsKey(table)) {
        tableToColumns.get(table).add(column);
      }
      else {
        ArrayList<String> cols = new ArrayList<String>();
        cols.add(column);
        tableToColumns.put(table, cols);
      }

      keyToIndex.put(fields[propIdx], tableToColumns.get(table).size());
      keyToType.put(fields[propIdx], fields[typeIdx].toUpperCase().contains("VARCHAR") ? "VARCHAR" : fields[typeIdx].toUpperCase());
    }
  }

  /*
   * Bind tuple values into insert statements.
   * @param tuple
   */
  @Override
  public void processTuple(HashMap<String, V> tuple) throws SQLException
  {
    if (tuple.isEmpty()) {
      emptyTuple = true;
    }
    for (Map.Entry<String, V> e: tuple.entrySet()) {
      tableToInsertStatement.get(keyToTable.get(e.getKey())).setObject(
              keyToIndex.get(e.getKey()).intValue(),
              e.getValue(),
              getSQLColumnType(keyToType.get(e.getKey())));
    }

    for (Map.Entry<String, PreparedStatement> entry: tableToInsertStatement.entrySet()) {
      entry.getValue().setObject(tableToColumns.get(entry.getKey()).size() + 1, windowId, Types.BIGINT);
      entry.getValue().setObject(tableToColumns.get(entry.getKey()).size() + 2, "0", Types.VARCHAR);
      entry.getValue().setObject(tableToColumns.get(entry.getKey()).size() + 3, operatorId, Types.VARCHAR);
    }
  }
}

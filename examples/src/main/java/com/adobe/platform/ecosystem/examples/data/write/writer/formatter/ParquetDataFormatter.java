
/*
 *  Copyright 2017-2018 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.adobe.platform.ecosystem.examples.data.write.writer.formatter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.adobe.platform.ecosystem.examples.data.write.mapper.MapperUtil;
import com.adobe.platform.ecosystem.examples.data.write.writer.extractor.Extractor;
import com.adobe.platform.ecosystem.examples.parquet.exception.ParquetIOException;
import com.adobe.platform.ecosystem.examples.parquet.model.ParquetIOField;
import com.adobe.platform.ecosystem.examples.catalog.model.DataSet;
import com.adobe.platform.ecosystem.examples.catalog.model.DataType;
import com.adobe.platform.ecosystem.examples.data.write.field.converter.parquet.ParquetFieldConverter;
import com.adobe.platform.ecosystem.examples.data.wiring.DataWiringParam;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;

import com.adobe.platform.ecosystem.examples.parquet.write.ParquetIOWriter;
import com.adobe.platform.ecosystem.examples.catalog.model.SDKField;
import com.adobe.platform.ecosystem.examples.catalog.model.SchemaField;
import com.adobe.platform.ecosystem.examples.constants.SDKConstants;
import com.adobe.platform.ecosystem.examples.data.write.Formatter;
import com.adobe.platform.ecosystem.examples.util.ConnectorSDKException;

import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Created by vardgupt on 10/17/2017.
 */

public class ParquetDataFormatter implements Formatter {

    private final ParquetIOWriter writer;

    private DataWiringParam param;

    private final ParquetFieldConverter<JSONObject> fieldConverter;

    private final Extractor<JSONObject> extractor;

    private static Logger logger = Logger.getLogger(ParquetDataFormatter.class.getName());

    public ParquetDataFormatter(ParquetIOWriter writer,
                                DataWiringParam param,
                                ParquetFieldConverter<JSONObject> fieldConverter,
                                Extractor<JSONObject> extractor) {
        this.writer = writer;
        this.param = param;
        this.fieldConverter = fieldConverter;
        this.extractor = extractor;
    }

    /**
     *  Constructing new fields with their mapped Catalog Flattened fields
     *  as tools internally coverts fields from "a.b.c" to "a_b_c".
     *  Type is set as "typeNotRequired" as reconciliation will be done in
     *  {@link ParquetFieldConverter} instance with Catalog fields.
     */
    @Override
    public byte[] getBuffer(List<SDKField> sdkFields, List<List<Object>> dataTable) throws ConnectorSDKException {
        try {

            // Match to the flattened fields.
            Map<String, String> mapSDKFields = new LinkedHashMap<>();
            for (SDKField field : sdkFields) {
                mapSDKFields.put(field.getName(), "");
            }
            param.getDataSet().matchFlattenedSchemaFields(mapSDKFields);

            List<SDKField> newFields = new ArrayList<>();

            for (SDKField field : sdkFields) {
                String mappedName = mapSDKFields.get(field.getName());
                if ("".equals(mappedName)) {
                    mappedName = field.getName();
                }
                newFields.add(new SDKField(mappedName, "typeNotRequired"));
            }

            return getBuffer(
                    MapperUtil.convert(
                            newFields,
                            dataTable
                    ));
        } catch (Exception ex) {
            logger.severe("Error while getting buffer from data table: " + ex);
            throw new ConnectorSDKException("Error while getting buffer from data table", ex);
        }
    }

    @Override
    public byte[] getBuffer(List<JSONObject> dataTable) throws ConnectorSDKException {
        try {
            // 1.Get schema from the first JSON object.
            JSONObject referenceObject = dataTable.get(0);
            List<ParquetIOField> parquetIOFields = fieldConverter.convert(referenceObject);

            // 2. Use the output from schema builder to get schema for parquet-IO SDK.
            MessageType schema = writer.getSchema(parquetIOFields);

            // 3. For each data row convert data object to Group record.
            List<SimpleGroup> records = new ArrayList<>();
            for (JSONObject row : dataTable) {
                SimpleGroup parquetRow = new SimpleGroup(schema);
                updateParquetGroupWithData(row, parquetRow);
                records.add(parquetRow);
            }

            Long timeStamp = 0l;
            timeStamp = System.currentTimeMillis();
            String fileId = timeStamp + "";
            byte[] buffer = getDataBuffer(fileId, records);
            return buffer;

        } catch (Exception ex) {
            logger.severe("Error while getting buffer from data table: " + ex);
            throw new ConnectorSDKException("Error while getting buffer from data table", ex);
        }
    }

    private void updateParquetGroupWithData(JSONObject data, SimpleGroup currentGroup) {
        GroupType schema = currentGroup.getType();
        int noOfFields = schema.getFieldCount();
        for (int i = 0; i < noOfFields; i++) {
            String currentFieldName = schema.getFieldName(i);
            if (schema.getType(i).isPrimitive()) {
                if (schema.getType(i).isRepetition(Type.Repetition.REPEATED)) {
                    if (data.get(currentFieldName) instanceof JSONArray) { // Regular case
                        // Below is an assumption that json array will be present as value.
                        JSONArray jsonValueArray = (JSONArray) data.get(currentFieldName);
                        for (int j = 0; j < jsonValueArray.size(); j++) {
                            Object value = jsonValueArray.get(j); // Value will be primitive.
                            updateParquetRecordWithPrimitiveValue(schema, value, currentGroup, i);
                        }
                    } else {
                        Object value = data.get(currentFieldName);
                        if(value instanceof String) {
                            final String strValue = (String) value;
                            if(strValue.split(",").length > 1) {
                                final int index = i;
                                Arrays.stream(strValue.split(",")).forEach( token -> {
                                    updateParquetRecordWithPrimitiveValue(schema, token, currentGroup, index);
                                });
                            }
                        } else {
                            updateParquetRecordWithPrimitiveValue(schema, value, currentGroup, i);
                        }
                    }
                } else if (schema.getType(i).isRepetition(Type.Repetition.OPTIONAL)) {
                    Object value = data.get(currentFieldName);
                    updateParquetRecordWithPrimitiveValue(schema, value, currentGroup, i);
                }
            } else {
                if (schema.getType(i).isRepetition(Type.Repetition.REPEATED)) {
                    if (data.get(currentFieldName) instanceof JSONArray) {
                        JSONArray jsonValueArray = (JSONArray) data.get(currentFieldName);
                        for (int j = 0; j < jsonValueArray.size(); j++) {
                            addComplexGroupToParquet(currentGroup, schema.getFieldName(i), (JSONObject) data.get(currentFieldName));
                        }
                    } else {
                        final JSONObject value = (JSONObject) data.get(currentFieldName);
                        if(extractor.isExtractRequired(value)) {
                            final List<JSONObject> objects = extractor.extract(value);
                            final int index = i;
                            objects.stream().forEach( extractedObject -> {
                                addComplexGroupToParquet(currentGroup, schema.getFieldName(index), extractedObject);
                            });
                        } else {
                            addComplexGroupToParquet(currentGroup, schema.getFieldName(i), (JSONObject) data.get(currentFieldName));
                        }
                    }
                } else {
                    addComplexGroupToParquet(currentGroup, schema.getFieldName(i), (JSONObject) data.get(currentFieldName));
                }
            }
        }
    }

    private void addComplexGroupToParquet(Group currentGroup, String fieldName, JSONObject jsonData){
        Group complexGroup = currentGroup.addGroup(fieldName);
        updateParquetGroupWithData(jsonData, (SimpleGroup) complexGroup);
    }

    private List<SimpleGroup> getRecords(List<SDKField> sdkFields, List<List<Object>> dataTable) {
        Map<String, String> map = new LinkedHashMap<>();
        Map<String, String> mapSDKFields = new LinkedHashMap<>();
        for (SDKField field : sdkFields) {
            mapSDKFields.put(field.getName(), "");
        }
        param.getDataSet().matchFlattenedSchemaFields(mapSDKFields);
        for (SDKField field : sdkFields) {
            String mappedName = mapSDKFields.get(field.getName());
            if("".equals(mappedName)) {
                mappedName = field.getName();
            }
            String mappedParquetType = parquetFieldConversionLambda.apply(mappedName, field.getType());
            map.put(mappedName, mappedParquetType);
            logger.log(Level.FINE, mappedName + " and " + mappedParquetType);
        }
        MessageType schema = writer.getSchema(map, SDKConstants.FIELDS_DELIM);
        List<SimpleGroup> records = new ArrayList<>();

        for (int rowId = 0; rowId < dataTable.size(); rowId++) {
            SimpleGroup record = new SimpleGroup(schema);
            getRecord(dataTable, sdkFields, schema, record, 0, rowId);//0 represents pointer is at 0th column of the record
            records.add(record);
        }
        return records;
    }

    private int getRecord(List<List<Object>> dataTable, List<SDKField> sdkFields, GroupType schema, SimpleGroup record, int fieldIndex, int rowId) {
        int noOfFields = schema.getFieldCount();
        logger.log(Level.FINE, schema.toString());
        int nextFieldIndex = fieldIndex;
        int leavesProcessed = 0;
        for (int p = 0; p < noOfFields; p++) {
            if (schema.getType(p).isPrimitive()) {
                Object fieldValue = dataTable.get(rowId).get(nextFieldIndex);
                nextFieldIndex = nextFieldIndex + 1;
                leavesProcessed++;
                updateParquetRecordWithPrimitiveValue(schema, fieldValue, record, p);
            } else {
                GroupType groupType = schema.getType(p).asGroupType();
                logger.log(Level.FINE, schema.getType(p).getName() + " " +  schema.getType(p).getRepetition());
                Group complexGroup = record.addGroup(schema.getFieldName(p));
                int leaveNodesProcessedRecurse = getRecord(dataTable, sdkFields, groupType, (SimpleGroup) complexGroup, nextFieldIndex, rowId);
                leavesProcessed = leavesProcessed + leaveNodesProcessedRecurse;
                nextFieldIndex = nextFieldIndex + leaveNodesProcessedRecurse;
            }
        }
        return leavesProcessed;
    }

    private byte[] getDataBuffer(String fileName, List<SimpleGroup> records) throws ConnectorSDKException {
        byte[] data = null;
        MessageType schema;
        if (records != null) {
            schema = (MessageType) records.get(0).getType();
            try {
                File f = writer.writeParquetFile(schema, fileName, records);
                logger.log(Level.FINE, "Local file written");
                data = Files.readAllBytes(Paths.get(f.getAbsolutePath()));
            } catch (IOException e) {
                throw new ConnectorSDKException("Error while executing getDataBuffer :" + e.getMessage(), e.getCause());
            } catch (ParquetIOException pioEx) {
                throw new ConnectorSDKException("Error from parquet IO library", pioEx);
            }
        }
        return data;
    }

    /**
     * Lambda expression to check if the
     * Catalog field type for current
     * SDK field is boolean or not.
     * This is done as tools treats
     * both <code>integer </code> and
     * <code>boolean</code> as 'integer' type
     * in it's own ecosystem. We need to differentiate
     * this while creating the parquet schema.
     */
    private Predicate<String> booleanCatalogFieldLambda = (sdkField -> {
        List<SchemaField> catalogFields = param.getDataSet().getFlattenedSchemaFields();
        for (SchemaField catalogField : catalogFields) {
            if (catalogField.getName().equals(sdkField) && catalogField.getType() == DataType.BooleanType) {
                return true;
            }
        }
        return false;
    });

    /**
     * Lambda expression to check if the
     * Catalog field type for current
     * SDK field is float or not.
     * This is done as tools treats
     * both <code>float</code> and
     * <code>double</code> as 'double' type
     * in it's own ecosystem. We need to differentiate
     * this while creating the parquet schema.
     */
    private Predicate<String> floatCatalogFieldLambda = (sdkField -> {
        List<SchemaField> catalogFields = param.getDataSet().getFlattenedSchemaFields();
        for (SchemaField catalogField : catalogFields) {
            if (catalogField.getName().equals(sdkField) && catalogField.getType() == DataType.FloatType) {
                return true;
            }
        }
        return false;
    });

    /**
     * Lambda expression to compute converted
     * SDKField(ETL tool type) to parquet
     * type.
     */
    private BiFunction<String, String, String> parquetFieldConversionLambda = (mappedName,type) -> {
        String mappedType = "";
        if (type.equalsIgnoreCase("string")) {
            mappedType = "binary";
        } else if (type.equalsIgnoreCase("integer")) {
            if (booleanCatalogFieldLambda.test(mappedName)) {
                mappedType = "boolean";
            } else {
                mappedType = "int32";
            }
        } else if (type.equalsIgnoreCase("bigint")) {
            mappedType = "int64";
        } else if (type.equalsIgnoreCase("double")) {
            if (floatCatalogFieldLambda.test(mappedName)) {
                mappedType = "float";
            } else {
                mappedType = "double";
            }
        }
        return mappedType;
    };

    /**
     * Method that invokes correct setter on
     * parquet record using the types defined
     * in parquet schema.
     *
     * @param schema             parquet schema of the GroupType record
     *                           of which current column is a part of.
     * @param currentColumnValue current column value.
     * @param currentRecord      Represents the current record in which
     *                           value will be updated for column <code>currentColumnIndex</code>
     * @param currentColumnIndex
     */
    private void updateParquetRecordWithPrimitiveValue(GroupType schema, Object currentColumnValue, SimpleGroup currentRecord, int currentColumnIndex) {
        if (currentColumnValue == null) {
            return;
        }
        PrimitiveType primitiveTypeField = schema.getType(currentColumnIndex).asPrimitiveType();
        String type = primitiveTypeField.getPrimitiveTypeName().name();
        if (type.equalsIgnoreCase("binary")) {
            if (currentColumnValue != null) {
                currentRecord.append(schema.getFieldName(currentColumnIndex), currentColumnValue.toString());
            }
        } else if (type.equalsIgnoreCase("boolean")) {
            currentRecord.add(schema.getFieldName(currentColumnIndex), getBooleanValueFromInt(currentColumnValue));
        } else if (type.equalsIgnoreCase("int32")) {
            currentRecord.add(schema.getFieldName(currentColumnIndex), getIntValue(currentColumnValue));
        } else if (type.equalsIgnoreCase("int64")) {
            currentRecord.add(schema.getFieldName(currentColumnIndex), getLongValue(currentColumnValue));
        } else if (type.equalsIgnoreCase("double")) {
            currentRecord.add(schema.getFieldName(currentColumnIndex), getDoubleValue(currentColumnValue));
        } else if (type.equalsIgnoreCase("float")) {
            currentRecord.add(schema.getFieldName(currentColumnIndex), getFloatValue(currentColumnValue));
        }
    }

    private int getIntValue(Object currentColumnValue) {
        return Integer.parseInt(currentColumnValue.toString());
    }

    private boolean getBooleanValueFromInt(Object fieldValue) {
        int integerValue = getIntValue(fieldValue);
        return integerValue != 0;
    }

    private long getLongValue(Object currentColumnValue) {
        return Long.parseLong(currentColumnValue.toString());
    }

    private double getDoubleValue(Object currentColumnValue) {
        return Double.parseDouble(currentColumnValue.toString());
    }

    private float getFloatValue(Object currentColumnValue) {
        return Float.parseFloat(currentColumnValue.toString());
    }
}

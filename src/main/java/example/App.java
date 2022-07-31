package example;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.file.*;
import org.apache.avro.generic.*;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.SchemaBuilder;
import org.camunda.bpm.dmn.engine.*;
import org.camunda.bpm.dmn.engine.impl.*;
import org.camunda.bpm.model.dmn.*;

/**
 * Hello world!
 *
 */
public class App {
    public static void main(String[] args) {
        // configure and build the DMN engine and get the decisions
        DmnEngine dmnEngine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
        List<DmnDecision> decisions = getDecisions(dmnEngine, "derivations.dmn");

        try {
            Schema inputSchema = new Schema.Parser().parse(new File("fake_dids_100.hash.mapped.avsc"));
            // System.out.println( inputSchema );
            // System.out.println( "" );

            Schema outputSchema = createOutputSchema(inputSchema, decisions);
            // System.out.println( outputSchema );

            // Create output file
            File outputFile = new File("fake_dids_100.hash.mapped.derived.avro");
            DatumWriter<GenericRecord> datumWriter = new GenericDatumWriter<GenericRecord>(outputSchema);
            DataFileWriter<GenericRecord> dataFileWriter = new DataFileWriter<GenericRecord>(datumWriter);
            dataFileWriter.create(outputSchema, outputFile);

            PrintWriter schemaWriter = new PrintWriter("fake_dids_100.hash.mapped.derived.avsc", "UTF-8");
            schemaWriter.println(outputSchema);
            schemaWriter.close();

            // Deserialize records from disk
            File file = new File("fake_dids_100.hash.mapped.avro");
            DatumReader<GenericRecord> datumReader = new GenericDatumReader<GenericRecord>(inputSchema);
            DataFileReader<GenericRecord> dataFileReader = new DataFileReader<GenericRecord>(file, datumReader);
            GenericRecord inputRecord = null;
            while (dataFileReader.hasNext()) {
                // Reuse inputRecord object by passing it to next(). This saves us from
                // allocating and garbage collecting many objects for files with
                // many items.
                inputRecord = dataFileReader.next(inputRecord);

                GenericRecord outputRecord = mergeRecordAndDmnResults(inputRecord, dmnEngine, decisions, outputSchema);
                // System.out.println(outputRecord);
                dataFileWriter.append(outputRecord);

                // System.out.println("");
            }

            dataFileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected static List<DmnDecision> getDecisions(DmnEngine dmnEngine, String filename) {
        // read a DMN model instance from a file
        DmnModelInstance dmnModelInstance = Dmn.readModelFromFile(new File(filename));

        // parse the decisions
        List<DmnDecision> decisions = dmnEngine.parseDecisions(dmnModelInstance);

        return decisions;
    }

    protected static Schema createOutputSchema(Schema inputSchema, List<DmnDecision> decisions) {
        // Deep copy input fields
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field f : inputSchema.getFields()) {
            Schema.Field _field = new Schema.Field(f.name(), f.schema(), f.doc(), f.defaultVal());
            fields.add(_field);
        }

        HashMap<String, String> dmnOutputs = getDecisionOutputs(decisions);
        for (Map.Entry m : dmnOutputs.entrySet()) {
            String outputName = String.valueOf(m.getKey());
            String outputType = String.valueOf(m.getValue());

            switch (outputType) {
                case "integer":
                    Schema.Field field = new Schema.Field(outputName, SchemaBuilder.builder().intType());
                    // Schema.Field field = new Schema.Field("new_field",
                    // SchemaBuilder.builder().intType(), "NewField", 10);
                    fields.add(field);

                    break;
                default:
                    String message = "Unsupported DMN type: " + outputType + " (for: " + outputName + ")";
                    System.out.println(message);
                    // TODO: Raise error
                    // throw new Exception(message);
            }
        }

        return Schema.createRecord(inputSchema.getName(), inputSchema.getDoc(), inputSchema.getNamespace(), false,
                fields);
    }

    protected static Map<String, Object> getAndCastFields(GenericRecord record) {
        Map<String, Object> fields = new HashMap<String, Object>();

        for (Schema.Field field : record.getSchema().getFields()) {
            // final LogicalType logicalType = field.schema().getLogicalType();
            Schema fieldSchema = field.schema().getTypes().get(0);
            final LogicalType logicalType = fieldSchema.getLogicalType();

            if (logicalType == LogicalTypes.date()) {
                int integerDate = ((Integer) record.get(field.name())).intValue();
                TimeConversions.DateConversion avroConversion = new TimeConversions.DateConversion();
                LocalDate date = avroConversion.fromInt(integerDate, fieldSchema, logicalType);

                fields.put(field.name(), date);
            } else {
                // throw new RuntimeException("Unsupported date type.");
                fields.put(field.name(), record.get(field.name()));
            }
        }
        return fields;
    }

    protected static GenericRecord cloneRecordWithOutputSchema(GenericRecord inputRecord, Schema outputSchema) {
        GenericRecord outputRecord = new GenericData.Record(outputSchema);

        for (Schema.Field field : inputRecord.getSchema().getFields()) {
            outputRecord.put(field.name(), inputRecord.get(field.name()));
        }

        return outputRecord;
    }

    protected static GenericRecord mergeRecordAndDmnResults(GenericRecord inputRecord, DmnEngine dmnEngine,
            List<DmnDecision> decisions, Schema outputSchema) {
        GenericRecord outputRecord = cloneRecordWithOutputSchema(inputRecord, outputSchema);
        Map<String, Object> fields = getAndCastFields(inputRecord);

        for (DmnDecision decision : decisions) {
            // evaluate a decision
            DmnDecisionResult result = dmnEngine.evaluateDecision(decision, fields);
            // DmnDecisionTableResult result = dmnEngine.evaluateDecisionTable(decision,
            // fields);

            List<Map<String, Object>> resultList = result.getResultList();
            for (int i = 0; i < resultList.size(); i++) {
                for (Map.Entry<String, Object> entry : resultList.get(i).entrySet()) {
                    outputRecord.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return outputRecord;
    }

    protected static HashMap<String, String> getDecisionOutputs(List<DmnDecision> decisions) {
        HashMap<String, String> map = new HashMap<String, String>();

        for (DmnDecision decision : decisions) {
            DmnDecisionLogic decisionLogic = decision.getDecisionLogic();

            if (decisionLogic instanceof DmnDecisionTableImpl) {
                // downcast DmnDecisionLogic to DmnDecisionTableImpl
                List<DmnDecisionTableOutputImpl> outputs = ((DmnDecisionTableImpl) decisionLogic).getOutputs();

                for (DmnDecisionTableOutputImpl output : outputs) {
                    map.put(output.getOutputName(), output.getTypeDefinition().getTypeName());
                }
            }
        }
        return map;
    }
}

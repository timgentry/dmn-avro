package example;

import java.io.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.avro.data.TimeConversions;
import org.apache.avro.file.*;
import org.apache.avro.generic.*;
import org.apache.avro.io.DatumReader;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.camunda.bpm.dmn.engine.*;
import org.camunda.bpm.model.dmn.*;

/**
 * Hello world!
 *
 */
public class App
{
    public static void main( String[] args )
    {
        // configure and build the DMN engine
        DmnEngine dmnEngine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();

        // read a DMN model instance from a file
        DmnModelInstance dmnModelInstance = Dmn.readModelFromFile(new File("derivations.dmn"));

        // parse the decisions
        List<DmnDecision> decisions = dmnEngine.parseDecisions(dmnModelInstance);

        try {
            Schema schema = new Schema.Parser().parse(new File("fake_dids_100.hash.mapped.avsc"));
            File file = new File("fake_dids_100.hash.mapped.avro");

            // Deserialize rows from disk
            DatumReader<GenericRecord> datumReader = new GenericDatumReader<GenericRecord>(schema);
            DataFileReader<GenericRecord> dataFileReader = new DataFileReader<GenericRecord>(file, datumReader);
            GenericRecord row = null;
            while (dataFileReader.hasNext()) {
                // Reuse row object by passing it to next(). This saves us from
                // allocating and garbage collecting many objects for files with
                // many items.
                row = dataFileReader.next(row);

                Map<String, Object> data = new HashMap<String, Object>();

                for(Schema.Field field : row.getSchema().getFields()) {
                    // final LogicalType logicalType = field.schema().getLogicalType();
                    Schema fieldSchema = field.schema().getTypes().get(0);
                    final LogicalType logicalType = fieldSchema.getLogicalType();

                    if (logicalType == LogicalTypes.date()) {
                        int integerDate = ((Integer) row.get(field.name())).intValue();
                        TimeConversions.DateConversion avroConversion = new TimeConversions.DateConversion();
                        LocalDate date = avroConversion.fromInt(integerDate, fieldSchema, logicalType);

                        data.put(field.name(), date);
                    } else {
                        // throw new RuntimeException("Unsupported date type.");
                        data.put(field.name(), row.get(field.name()));
                    }
                }

                for(DmnDecision decision : decisions) {
                    // evaluate a decision
                    DmnDecisionResult result = dmnEngine.evaluateDecision(decision, data);
                    // DmnDecisionTableResult result = dmnEngine.evaluateDecisionTable(decision, data);
                    System.out.println( decision.getName() );
                    System.out.println( result );
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

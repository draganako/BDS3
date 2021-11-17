package com.spark;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

public class RowSchema {
    
    public static StructType getRowSchema() {

        StructField[] fields = new StructField[] {
            DataTypes.createStructField("MONTH", DataTypes.IntegerType, true),
            DataTypes.createStructField("DEP_DEL15", DataTypes.DoubleType, true),
            DataTypes.createStructField("DISTANCE_GROUP", DataTypes.DoubleType, true),
            DataTypes.createStructField("DEP_BLOCK", DataTypes.StringType, true),
            DataTypes.createStructField("CARRIER_NAME", DataTypes.StringType, true),
            DataTypes.createStructField("PLANE_AGE", DataTypes.IntegerType, true),
            DataTypes.createStructField("DEPARTING_AIRPORT", DataTypes.StringType, true),
            DataTypes.createStructField("PREVIOUS_AIRPORT", DataTypes.StringType, true),
            DataTypes.createStructField("PRCP", DataTypes.DoubleType, true),
            DataTypes.createStructField("SNOW", DataTypes.DoubleType, true),
            DataTypes.createStructField("AWND", DataTypes.DoubleType, true),
                           
        };
        
        return DataTypes.createStructType(fields);
    }
}

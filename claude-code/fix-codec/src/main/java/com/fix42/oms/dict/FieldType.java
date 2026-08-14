package com.fix42.oms.dict;

/** Coarse FIX 4.2 field value types, enough to drive typed mapping and validation. */
public enum FieldType {
    STRING,
    CHAR,
    INT,
    QTY,
    PRICE,
    AMT,
    FLOAT,
    BOOLEAN,
    CURRENCY,
    EXCHANGE,
    UTC_TIMESTAMP,
    UTC_DATE,
    MULTIPLE_VALUE_STRING,
    NUM_IN_GROUP,
    LENGTH,
    DATA
}

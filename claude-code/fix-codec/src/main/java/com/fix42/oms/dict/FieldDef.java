package com.fix42.oms.dict;

/** Definition of a single FIX field: its tag, canonical name, and value type. */
public record FieldDef(int tag, String name, FieldType type) {
}

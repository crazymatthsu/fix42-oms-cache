package com.fix42.oms.dict;

public final class FieldDef {
    private final int tag;
    private final String name;
    private final FieldType type;

    public FieldDef(int tag, String name, FieldType type) {
        this.tag = tag;
        this.name = name;
        this.type = type;
    }

    public int tag() {
        return tag;
    }

    public String name() {
        return name;
    }

    public FieldType type() {
        return type;
    }
}

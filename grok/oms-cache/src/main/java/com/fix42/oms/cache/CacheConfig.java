package com.fix42.oms.cache;

import com.fix42.oms.api.OrderStateHandler;
import com.fix42.oms.fix.Tags;
import com.fix42.oms.persist.PersistenceConfig;

public final class CacheConfig {
    private final int historyLimit;
    private final boolean validateChecksum;
    private final boolean strictHeader;
    private final boolean applyStaleExecReports;
    private final int parentOrderIdTag;
    private final int parentClOrdIdTag;
    private final int seenExecIdLimit;
    private final PersistenceConfig persistence;
    private final OrderStateHandler orderStateHandler;
    private final boolean handlerFailsIngest;

    private CacheConfig(Builder builder) {
        this.historyLimit = builder.historyLimit;
        this.validateChecksum = builder.validateChecksum;
        this.strictHeader = builder.strictHeader;
        this.applyStaleExecReports = builder.applyStaleExecReports;
        this.parentOrderIdTag = builder.parentOrderIdTag;
        this.parentClOrdIdTag = builder.parentClOrdIdTag;
        this.seenExecIdLimit = builder.seenExecIdLimit;
        this.persistence = builder.persistence;
        this.orderStateHandler = builder.orderStateHandler;
        this.handlerFailsIngest = builder.handlerFailsIngest;
    }

    public static CacheConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int historyLimit() {
        return historyLimit;
    }

    public boolean validateChecksum() {
        return validateChecksum;
    }

    public boolean strictHeader() {
        return strictHeader;
    }

    public boolean applyStaleExecReports() {
        return applyStaleExecReports;
    }

    public int parentOrderIdTag() {
        return parentOrderIdTag;
    }

    public int parentClOrdIdTag() {
        return parentClOrdIdTag;
    }

    public int seenExecIdLimit() {
        return seenExecIdLimit;
    }

    public PersistenceConfig persistence() {
        return persistence;
    }

    public OrderStateHandler orderStateHandler() {
        return orderStateHandler;
    }

    public boolean handlerFailsIngest() {
        return handlerFailsIngest;
    }

    public static final class Builder {
        private int historyLimit = 32;
        private boolean validateChecksum = true;
        private boolean strictHeader = true;
        private boolean applyStaleExecReports = false;
        private int parentOrderIdTag = Tags.PARENT_ORDER_ID;
        private int parentClOrdIdTag = Tags.PARENT_CL_ORD_ID;
        private int seenExecIdLimit = 64;
        private PersistenceConfig persistence;
        private OrderStateHandler orderStateHandler;
        private boolean handlerFailsIngest = true;

        public Builder historyLimit(int historyLimit) {
            if (historyLimit < 0) {
                throw new IllegalArgumentException("historyLimit must be >= 0");
            }
            this.historyLimit = historyLimit;
            return this;
        }

        public Builder validateChecksum(boolean validateChecksum) {
            this.validateChecksum = validateChecksum;
            return this;
        }

        public Builder strictHeader(boolean strictHeader) {
            this.strictHeader = strictHeader;
            return this;
        }

        public Builder applyStaleExecReports(boolean applyStaleExecReports) {
            this.applyStaleExecReports = applyStaleExecReports;
            return this;
        }

        public Builder parentOrderIdTag(int parentOrderIdTag) {
            this.parentOrderIdTag = parentOrderIdTag;
            return this;
        }

        public Builder parentClOrdIdTag(int parentClOrdIdTag) {
            this.parentClOrdIdTag = parentClOrdIdTag;
            return this;
        }

        public Builder seenExecIdLimit(int seenExecIdLimit) {
            if (seenExecIdLimit < 1) {
                throw new IllegalArgumentException("seenExecIdLimit must be >= 1");
            }
            this.seenExecIdLimit = seenExecIdLimit;
            return this;
        }

        public Builder persistence(PersistenceConfig persistence) {
            this.persistence = persistence;
            return this;
        }

        public Builder orderStateHandler(OrderStateHandler orderStateHandler) {
            this.orderStateHandler = orderStateHandler;
            return this;
        }

        /**
         * When true (default), a thrown handler fails {@code ingest} so the
         * caller can withhold the drop-copy ack. When false, handler errors
         * are swallowed.
         */
        public Builder handlerFailsIngest(boolean handlerFailsIngest) {
            this.handlerFailsIngest = handlerFailsIngest;
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(this);
        }
    }
}

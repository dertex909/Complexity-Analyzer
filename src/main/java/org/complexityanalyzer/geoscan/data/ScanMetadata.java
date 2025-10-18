package org.complexityanalyzer.geoscan.data;

public record ScanMetadata(ScanPhase scanPhase) {

    public enum ScanPhase {

        IDLE,

        RECONNAISSANCE,

        REFINING,

        COMPLETE
    }
}
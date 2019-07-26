package org.hackton.analyzer.domain;

public class CarParkStatus {
    /**
      0 = General
      1 = Shift
      2 = Reserved
     */
    private String carParkTypeId;
    /**
     * count = FULL (if used == total) else count = used
     */
    private String count;
}
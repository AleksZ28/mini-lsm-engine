package com.ibm.internship.lsm.web.dto;

import java.io.Serializable;

public record EntryRequest(String key, String value) implements Serializable {}

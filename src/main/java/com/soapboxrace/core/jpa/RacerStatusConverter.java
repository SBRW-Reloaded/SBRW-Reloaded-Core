/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.jpa;

import com.soapboxrace.core.bo.util.RacerStatus;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

@Converter(autoApply = false)
public class RacerStatusConverter implements AttributeConverter<RacerStatus, Integer> {

    @Override
    public Integer convertToDatabaseColumn(RacerStatus attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.racerStatus();
    }

    @Override
    public RacerStatus convertToEntityAttribute(Integer dbData) {
        if (dbData == null) {
            return null;
        }
        return RacerStatus.fromCode(dbData);
    }
}
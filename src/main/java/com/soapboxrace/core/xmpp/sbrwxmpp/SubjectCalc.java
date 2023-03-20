/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.xmpp.sbrwxmpp;

class SubjectCalc {
    private static long binhash64(char[] str, long init) {
        for (char c : str) {
            init = (init * 33) + c;
        }
        return init;
    }

    static long calculateHash(char[] jid, char[] msg) {
        long hash = binhash64(jid, -1);
        return binhash64(msg, hash);
    }
}

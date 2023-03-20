package com.soapboxrace.core.bo.util;

import java.util.List;
import java.util.ArrayList;

public class KonamiDecode {
    public static String getHacksType(Integer code, String ref) {
        if(code == 0) {
            return "No Cheat Detected";
        }

        String[] detectedList = new String[] { };

        if(ref == "konami") {
            detectedList = new String[] { "MultiHack", "FastPowerups", "SpeedHack", "SmoothWalls", "TankMode", "Wallhack", "DriftMods", "NoCops", "ProfileMasker", "Ghosting", "", "", "", "" };
        } else if (ref == "hacksDetected") {
            detectedList = new String[] { "Autofinish", "", "", "Modified Attributes", "", "Modified GameFiles", "", "", "", "", "", "", "", ""};
        } else {
            return "Code " + code;
        }

        if(code > (1 << detectedList.length)) {
            return "Code " + code;
        }

        int CheatDetectorId = 0;
        
        StringBuilder inversedDetected = new StringBuilder();
        inversedDetected.append(Integer.toString(code, 2));
        inversedDetected.reverse();
        
        String[] detected = new String(inversedDetected).split("");
        List<String> detectedCheatType = new ArrayList<>();
        
        for (String singlecheat : detected) { 
            if(singlecheat.equals("1")) {
                if(detectedList[CheatDetectorId] != "") {
                    detectedCheatType.add(detectedList[CheatDetectorId]);
                }
            }
            
            CheatDetectorId++;
        }
        
        String output = "Code " + code;

        if(!detectedCheatType.isEmpty()) {
            output = "Code " + code + ": " + String.join(", ", detectedCheatType);
        }
        
        return output;
    }
}

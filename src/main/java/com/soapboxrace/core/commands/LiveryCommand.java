package com.soapboxrace.core.commands;

import java.time.LocalDateTime;

import javax.ws.rs.core.Response;

import java.util.Random;
import java.util.Set;
import java.util.List;

import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.bo.util.HelpingTools;
import com.soapboxrace.core.bo.*;
import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;

public class LiveryCommand {
    public Response Command(
        String command_unsplitted, 
        OpenFireSoapBoxCli openFireSoapBoxCli, 
        PersonaEntity personaEntity, 
        LiveryStoreDAO liveryStoreDao, 
        VinylDAO vinylDao, 
        LiveryStoreDataDAO liveryStoreDataDao, 
        ParameterBO parameterBO, 
        PersonaBO personaBO,
        VinylProductDAO vinylProductDAO
    ) {
        /* Command construction 
         * 
         * livery import <code> (--force)
         * livery export
        */

        String[] command = command_unsplitted.split(" ");
        CarEntity carEntity = personaBO.getDefaultCarEntity(personaEntity.getPersonaId());

        if(command.length < 2) {
            openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_NOOPTION"), personaEntity.getPersonaId());
        } else {
            if(command[1].trim().equals("import")) {                
                if(command.length >= 3) {
                    String liverycode = command[2].trim();
                    LiveryStoreEntity liveryStoreEntity = liveryStoreDao.findLiveryByCode(liverycode);
                    if(liveryStoreEntity == null) {
                        openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_IMPORT_NONEXISTENT"), personaEntity.getPersonaId());
                    } else {
                        if(liveryStoreEntity.getIsbanned() == true) {
                            openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_IMPORT_BANNED"), personaEntity.getPersonaId());
                        } else {
                            Boolean canImport = carEntity.getName().trim().equals(liveryStoreEntity.getCarname());

                            if(!canImport) {
                                canImport = command_unsplitted.contains("--force");
                            }

                            if(canImport) {
                                //Increment a count for this one:
                                LiveryStoreEntity temporary = liveryStoreDao.findLiveryByCode(liverycode);
                                temporary.setUseCount(liveryStoreEntity.getUseCount() + 1);
                                liveryStoreDao.update(temporary);

                                VinylEntity oldLiveries = vinylDao.findByCarId(carEntity.getId());
                                if(oldLiveries != null) {
                                    vinylDao.deleteByCar(carEntity);
                                }

                                //Add new ones to it
                                for (LiveryStoreDataEntity vinyl : liveryStoreDataDao.getVinylsByCode(liverycode)) {
                                    VinylEntity DataEntity = new VinylEntity();
                                    DataEntity.setCar(carEntity);
                                    DataEntity.setHash(vinyl.getHash());
                                    DataEntity.setHue1(vinyl.getHue1());
                                    DataEntity.setHue2(vinyl.getHue2());
                                    DataEntity.setHue3(vinyl.getHue3());
                                    DataEntity.setHue4(vinyl.getHue4());
                                    DataEntity.setLayer(vinyl.getLayer());
                                    DataEntity.setMir(vinyl.isMir());
                                    DataEntity.setRot(vinyl.getRot());
                                    DataEntity.setSat1(vinyl.getSat1());
                                    DataEntity.setSat2(vinyl.getSat2());
                                    DataEntity.setSat3(vinyl.getSat3());
                                    DataEntity.setSat4(vinyl.getSat4());
                                    DataEntity.setScalex(vinyl.getScalex());
                                    DataEntity.setScaley(vinyl.getScaley());
                                    DataEntity.setShear(vinyl.getShear());
                                    DataEntity.setTranx(vinyl.getTranx());
                                    DataEntity.setTrany(vinyl.getTrany());
                                    DataEntity.setVar1(vinyl.getVar1());
                                    DataEntity.setVar2(vinyl.getVar2());
                                    DataEntity.setVar3(vinyl.getVar3());
                                    DataEntity.setVar4(vinyl.getVar4());
                                    vinylDao.insert(DataEntity);
                                }

                                openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_IMPORT_SUCCESS"), personaEntity.getPersonaId());
                            } else {
                                openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_IMPORT_NOTCOMPATIBLE," + liverycode), personaEntity.getPersonaId());
                            }
                        }
                    }

                } else {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_IMPORT_UNKNOWN"), personaEntity.getPersonaId());
                }
            } else if(command[1].trim().equals("export")) {
                //generate the code first

                if(carEntity != null) {
                    Set<VinylEntity> vinyls = carEntity.getVinyls();

                    if(vinyls.size() == 0) {
                        openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_EXPORT_NONEXISTENT"), personaEntity.getPersonaId());
                    } else {
                        String code = generatedCode(parameterBO.getIntParam("SBRWR_LIVERYCODE_LENGTH", 8), liveryStoreDao);

                        LiveryStoreEntity liveryStoreEntity = new LiveryStoreEntity();
                        liveryStoreEntity.setPersonaId(personaEntity.getPersonaId());
                        liveryStoreEntity.setCode(code);
                        liveryStoreEntity.setCreated(LocalDateTime.now());
                        liveryStoreEntity.setCarname(carEntity.getName());
                        liveryStoreEntity.setIsbanned(false);
                        liveryStoreEntity.setUseCount(0);
                        liveryStoreDao.insert(liveryStoreEntity);

                        Integer counter = 1;
        
                        //now lets save car vinyls, but first, fetch them!
                        for (VinylEntity vinyl : vinyls) {
                            LiveryStoreDataEntity DataEntity = new LiveryStoreDataEntity();
                            DataEntity.setOrderid(counter);
                            DataEntity.setHash(vinyl.getHash());
                            DataEntity.setHue1(vinyl.getHue1());
                            DataEntity.setHue2(vinyl.getHue2());
                            DataEntity.setHue3(vinyl.getHue3());
                            DataEntity.setHue4(vinyl.getHue4());
                            DataEntity.setLayer(vinyl.getLayer());
                            DataEntity.setMir(vinyl.isMir());
                            DataEntity.setRot(vinyl.getRot());
                            DataEntity.setSat1(vinyl.getSat1());
                            DataEntity.setSat2(vinyl.getSat2());
                            DataEntity.setSat3(vinyl.getSat3());
                            DataEntity.setSat4(vinyl.getSat4());
                            DataEntity.setScalex(vinyl.getScalex());
                            DataEntity.setScaley(vinyl.getScaley());
                            DataEntity.setShear(vinyl.getShear());
                            DataEntity.setTranx(vinyl.getTranx());
                            DataEntity.setTrany(vinyl.getTrany());
                            DataEntity.setVar1(vinyl.getVar1());
                            DataEntity.setVar2(vinyl.getVar2());
                            DataEntity.setVar3(vinyl.getVar3());
                            DataEntity.setVar4(vinyl.getVar4());
                            DataEntity.setLiverycode(code);
                            liveryStoreDataDao.insert(DataEntity);
                            counter++;
                        }

                        openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_EXPORT_SUCCESS," + code), personaEntity.getPersonaId());
                    }
                } else {
                    openFireSoapBoxCli.send(XmppChat.createSystemMessage("SBRWR_LIVERY_EXPORT_NONEXISTENT"), personaEntity.getPersonaId());
                }
            } else if(command[1].trim().equals("nft")) {
                VinylEntity oldLiveries = vinylDao.findByCarId(carEntity.getId());
                if(oldLiveries != null) {
                    vinylDao.deleteByCar(carEntity);
                }

                Integer howManyLayers = new Random().nextInt(30) + 1;

                // Si le joueur a un prestige supérieur à 0, utiliser le niveau 60 pour débloquer tous les vinyls
                int effectiveLevel = personaEntity.getPrestige() > 0 ? 60 : personaEntity.getLevel();
                List<VinylProductEntity> vinylProductEntity = vinylProductDAO.getAllByLevelEnabled(effectiveLevel, true, personaEntity.getUser().isPremium());

                for (int i = 0; i < howManyLayers; ++i) {
                    Integer hash = vinylProductEntity.get(new Random().nextInt(vinylProductEntity.size())).getHash();

                    VinylEntity DataEntity = new VinylEntity();
                    DataEntity.setCar(carEntity);
                    DataEntity.setHash(hash);
                    DataEntity.setHue1(new Random().nextInt());
                    DataEntity.setHue2(new Random().nextInt());
                    DataEntity.setHue3(new Random().nextInt());
                    DataEntity.setHue4(new Random().nextInt());
                    DataEntity.setLayer(i);
                    DataEntity.setMir(new Random().nextBoolean());
                    DataEntity.setRot(new Random().nextInt());
                    DataEntity.setSat1(new Random().nextInt());
                    DataEntity.setSat2(new Random().nextInt());
                    DataEntity.setSat3(new Random().nextInt());
                    DataEntity.setSat4(new Random().nextInt());
                    DataEntity.setScalex(new Random().nextInt());
                    DataEntity.setScaley(new Random().nextInt());
                    DataEntity.setShear(new Random().nextInt());
                    DataEntity.setTranx(new Random().nextInt());
                    DataEntity.setTrany(new Random().nextInt());
                    DataEntity.setVar1(new Random().nextInt());
                    DataEntity.setVar2(new Random().nextInt());
                    DataEntity.setVar3(new Random().nextInt());
                    DataEntity.setVar4(new Random().nextInt());
                    vinylDao.insert(DataEntity);
                }

                openFireSoapBoxCli.send(XmppChat.createSystemMessage("Yes, sure! Here are " + howManyLayers + " layers of vinyls applied!"), personaEntity.getPersonaId());
            }
        }

        return Response.noContent().build();
	}

    public String generatedCode(int length, LiveryStoreDAO liveryStoreDao) {
        String checkCode = HelpingTools.generateCode(length);
        String bannedWords = "ass;azz;c0c;coc;cok;cox;cum;dak;dic;dik;diq;dlc;dlk;dlq;dyc;f@G;f0k;fag;faj;fap;fck;fkg;fkn;fku;fok;fuc;fud;fuk;fuo;fuq;fux;fvc;fvk;gay;haj;h-o;jap;jig;jiw;jiz;jlg;joi;juw;kkk";

        for (String singleword : bannedWords.split(";")) {
            if(checkCode.contains(singleword)) {
                return generatedCode(length, liveryStoreDao);
            }
        }

        LiveryStoreEntity liveryStoreEntity = liveryStoreDao.findLiveryByCode(checkCode);
        return (liveryStoreEntity == null) ? checkCode : generatedCode(length, liveryStoreDao);
    }
}

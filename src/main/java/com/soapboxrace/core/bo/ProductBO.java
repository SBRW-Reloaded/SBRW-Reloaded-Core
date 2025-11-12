/*
 * This file is part of the Soapbox Race World core source code.
 * If you use any of this code for third-party purposes, please provide attribution.
 * Copyright (c) 2020.
 */

package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.CategoryDAO;
import com.soapboxrace.core.dao.PersonaDAO;
import com.soapboxrace.core.dao.ProductDAO;
import com.soapboxrace.core.dao.VinylProductDAO;
import com.soapboxrace.core.jpa.CategoryEntity;
import com.soapboxrace.core.jpa.PersonaEntity;
import com.soapboxrace.core.jpa.ProductEntity;
import com.soapboxrace.core.jpa.VinylProductEntity;
import com.soapboxrace.jaxb.http.ArrayOfProductTrans;
import com.soapboxrace.jaxb.http.ProductTrans;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import java.util.ArrayList;
import java.util.List;

@Stateless
public class ProductBO {

    @EJB
    private ProductDAO productDAO;

    @EJB
    private CategoryDAO categoryDao;

    @EJB
    private VinylProductDAO vinylProductDao;

    @EJB
    private PersonaDAO personaDao;

    public List<ProductTrans> getProductTransList(List<ProductEntity> productEntities) {
        List<ProductTrans> productTransList = new ArrayList<>();

        for (ProductEntity productEntity : productEntities) {
            productTransList.add(productEntityToProductTrans(productEntity));
        }

        return productTransList;
    }

    public List<ProductTrans> getProductTransList(List<ProductEntity> productEntities, Long personaId) {
        List<ProductTrans> productTransList = new ArrayList<>();
        boolean hasPrestige = false;

        if (personaId != null && !personaId.equals(0L)) {
            PersonaEntity personaEntity = personaDao.find(personaId);
            hasPrestige = personaEntity.getPrestige() > 0;
        }

        for (ProductEntity productEntity : productEntities) {
            productTransList.add(productEntityToProductTrans(productEntity, hasPrestige));
        }

        return productTransList;
    }

    private ProductTrans productEntityToProductTrans(ProductEntity productEntity) {
        return productEntityToProductTrans(productEntity, false);
    }

    private ProductTrans productEntityToProductTrans(ProductEntity productEntity, boolean hasPrestige) {
        ProductTrans productTrans = new ProductTrans();
        productTrans.setBundleItems(new ArrayOfProductTrans());
        productTrans.setCurrency(productEntity.getCurrency());
        productTrans.setDurationMinute(productEntity.getDurationMinute());
        productTrans.setDescription(productEntity.getDescription());
        productTrans.setHash(productEntity.getHash());
        productTrans.setIcon(productEntity.getIcon());
        productTrans.setSecondaryIcon(productEntity.getSecondaryIcon());
        // Pour les joueurs prestigés, appliquer la même logique que pour les voitures aux pièces de performance
        if (hasPrestige) {
            if ("NFSW_NA_EP_PRESET_RIDES_ALL_Category".equals(productEntity.getCategoryName()) && productEntity.getLevel() <= 100) {
                productTrans.setLevel(1); // Afficher niveau 1 au client pour les voitures ≤ niveau 100
            } else if ("NFSW_NA_EP_PERFORMANCEPARTS".equals(productEntity.getCategoryName()) || 
                       "NFSW_NA_EP_SKILLMODPARTS".equals(productEntity.getCategoryName())) {
                productTrans.setLevel(1); // Afficher niveau 1 au client pour les pièces de performance et skill mods
            } else {
                productTrans.setLevel(productEntity.getLevel()); // Niveau réel pour les autres cas
            }
        } else {
            productTrans.setLevel(productEntity.getLevel()); // Niveau réel pour les joueurs sans prestige
        }
        productTrans.setLongDescription(productEntity.getLongDescription());
        productTrans.setPrice(productEntity.getPrice());
        productTrans.setPriority(productEntity.getPriority());
        productTrans.setProductId(productEntity.getProductId());
        productTrans.setProductTitle(productEntity.getProductTitle());
        productTrans.setProductType(productEntity.getProductType());
        productTrans.setUseCount(productEntity.getUseCount());
        productTrans.setSecondaryIcon(productEntity.getSecondaryIcon());
        productTrans.setVisualStyle(productEntity.getVisualStyle());
        productTrans.setWebIcon(productEntity.getWebIcon());
        productTrans.setWebLocation(productEntity.getWebLocation());

        for (ProductEntity bundledProductEntity : productEntity.getBundleItems()) {
            productTrans.getBundleItems().getProductTrans().add(productEntityToProductTrans(bundledProductEntity, hasPrestige));
        }

        return productTrans;
    }

    public List<ProductEntity> productsInCategory(String categoryName, String productType, Long personaId) {
        boolean premium = false;
        boolean admin = false;
        int level = 1;
        if (personaId != null && !personaId.equals(0L)) {
            PersonaEntity personaEntity = personaDao.find(personaId);
            premium = personaEntity.getUser().isPremium();
            admin = personaEntity.getUser().isAdmin();
            // Si le joueur a un prestige supérieur à 0, considérer qu'il est niveau 60 pour débloquer tous les items
            if (personaEntity.getPrestige() > 0) {
                level = 60;
            } else {
                level = personaEntity.getLevel();
            }
        }
        List<ProductEntity> productEntities = productDAO.findByLevelEnabled(categoryName, productType, level, true, premium, admin);

        for (ProductEntity product : productEntities) {
            product.getBundleItems().size();
        }
        return productEntities;
    }

    public ProductEntity getRandomDrop(String productType) {
        List<ProductEntity> productEntities = productDAO.findDropsByType(productType);

        if (productEntities.isEmpty()) {
            throw new RuntimeException("No droppable products of type '" + productType + "' to work with!");
        }

        double weightSum = productEntities.stream().mapToDouble(p -> getDropWeight(p, productEntities)).sum();

        int randomIndex = -1;
        double random = Math.random() * weightSum;

        for (int i = 0; i < productEntities.size(); i++) {
            random -= getDropWeight(productEntities.get(i), productEntities);

            if (random <= 0.0d) {
                randomIndex = i;
                break;
            }
        }

        if (randomIndex == -1) {
            throw new RuntimeException("Random selection failed for products of type " + productType + " (weightSum=" + weightSum + ")");
        }

        return productEntities.get(randomIndex);
    }

    public List<CategoryEntity> categories() {
        return categoryDao.getAll();
    }

    public ArrayOfProductTrans getVinylByCategory(CategoryEntity categoryEntity, Long personaId) {
        boolean premium = false;
        boolean hasPrestige = false;
        int level = 1;
        if (personaId != null && !personaId.equals(0L)) {
            PersonaEntity personaEntity = personaDao.find(personaId);
            premium = personaEntity.getUser().isPremium();
            hasPrestige = personaEntity.getPrestige() > 0;
            // Si le joueur a un prestige supérieur à 0, considérer qu'il est niveau 60 pour débloquer tous les items
            if (hasPrestige) {
                level = 60;
            } else {
                level = personaEntity.getLevel();
            }
        }
        ArrayOfProductTrans arrayOfProductTrans = new ArrayOfProductTrans();
        List<VinylProductEntity> vinylProductEntity = vinylProductDao.findByCategoryLevelEnabled(categoryEntity,
                level, true, premium);
        for (VinylProductEntity entity : vinylProductEntity) {
            ProductTrans productTrans = new ProductTrans();
            productTrans.setCurrency(entity.getCurrency());
            productTrans.setDurationMinute(entity.getDurationMinute());
            productTrans.setHash(entity.getHash());
            productTrans.setIcon(entity.getIcon());
            productTrans.setSecondaryIcon(entity.getSecondaryIcon());
            // Toujours envoyer le niveau réel du produit au client
            productTrans.setLevel(entity.getLevel());
            productTrans.setPrice(entity.getPrice());
            productTrans.setPriority(entity.getPriority());
            productTrans.setProductId(entity.getProductId());
            productTrans.setProductTitle(entity.getProductTitle());
            productTrans.setProductType(entity.getProductType());
            productTrans.setUseCount(entity.getUseCount());
            arrayOfProductTrans.getProductTrans().add(productTrans);
        }
        return arrayOfProductTrans;
    }

    private double getDropWeight(ProductEntity p, List<ProductEntity> productEntities) {
        if (p.getDropWeight() == null) {
            return 1.0d / productEntities.size();
        }

        return p.getDropWeight();
    }
}

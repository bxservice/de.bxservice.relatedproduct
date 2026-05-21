/**********************************************************************
* This file is part of iDempiere ERP Open Source                      *
* http://www.idempiere.org                                            *
*                                                                     *
* Copyright (C) Contributors                                          *
*                                                                     *
* This program is free software; you can redistribute it and/or       *
* modify it under the terms of the GNU General Public License         *
* as published by the Free Software Foundation; either version 2      *
* of the License, or (at your option) any later version.              *
*                                                                     *
* This program is distributed in the hope that it will be useful,     *
* but WITHOUT ANY WARRANTY; without even the implied warranty of      *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
* GNU General Public License for more details.                        *
*                                                                     *
* You should have received a copy of the GNU General Public License   *
* along with this program; if not, write to the Free Software         *
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
* MA 02110-1301, USA.                                                 *
*                                                                     *
* Contributors:                                                       *
* - Diego Ruiz - BX Service GmbH                                      *
**********************************************************************/
package de.bxservice.relatedproduct.utils;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.logging.Level;

import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MDocType;
import org.compiere.model.MInventory;
import org.compiere.model.MInventoryLine;
import org.compiere.model.MProduct;
import org.compiere.util.CLogger;
import org.compiere.util.Env;

import de.bxservice.model.MRelatedProduct;

public class RelatedInventoryHandler {

	private static CLogger log = CLogger.getCLogger(RelatedInventoryHandler.class);

	/**
	 * Create new lines for related products on a physical inventory or internal use inventory line.
	 * Not applicable for cost adjustment inventories.
	 * QtyCount and QtyInternalUse are set to ZERO — {@code MInventoryLine.beforeSave} will
	 * populate the correct stock qty field based on the document sub-type.
	 *
	 * @param inventoryLine the master inventory line that triggered the event
	 * @param type          the event topic (PO_AFTER_NEW or PO_AFTER_CHANGE)
	 */
	public static void createRelatedInventoryLines(MInventoryLine inventoryLine, String type) {

		MInventory inventory = inventoryLine.getParent();

		if (!isSupportedInventoryType(inventory))
			return;

		MProduct product = inventoryLine.getProduct();

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {
			try {
				if (log.isLoggable(Level.INFO))
					log.info("Creating related products for: " + product.getName() + " in inventory: " + inventory.get_ID());

				int lineNo = inventoryLine.getLine();

				// If the record was modified delete previous related lines to avoid duplicates
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)) {
					if (!deleteRelatedInventoryLines(inventoryLine, true))
						return;
				}

				for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {
					if (relatedProduct.isConditionalUOM(inventoryLine.getC_UOM_ID())) {

						BigDecimal qty = relatedProduct.get_ValueAsInt("Qty") != 0
								? BigDecimal.valueOf(relatedProduct.get_ValueAsInt("Qty")).multiply(inventoryLine.getQtyEntered())
								: BigDecimal.ONE;

						// QtyCount and QtyInternalUse are intentionally set to ZERO.
						// MInventoryLine.beforeSave will set the correct stock qty field
						// (QtyCount or QtyInternalUse) based on the document sub-type.
						MInventoryLine newLine = new MInventoryLine(inventory,
								inventoryLine.getM_Locator_ID(),
								relatedProduct.getRelatedProduct_ID(),
								0 /* M_AttributeSetInstance_ID */,
								Env.ZERO /* QtyBook */,
								Env.ZERO /* QtyCount — set by beforeSave */,
								Env.ZERO /* QtyInternalUse — set by beforeSave */);

						newLine.setLine(++lineNo);
						newLine.setQtyEntered(qty);

						if (relatedProduct.getDescription() != null)
							newLine.setDescription(relatedProduct.getDescription());
						
						if (inventoryLine.getC_Charge_ID() != 0)
							newLine.setC_Charge_ID(inventoryLine.getC_Charge_ID());

						newLine.set_ValueOfColumn(RelatedProductConstants.MasterInventoryLine_COLUMN_NAME, inventoryLine.get_ID());
						newLine.saveEx(inventory.get_TrxName());

						if (log.isLoggable(Level.INFO))
							log.info("A new inventory line was added with product: " + relatedProduct.getRelatedProduct_ID());
					}
				}
			} catch (Exception e) {
				throw new AdempiereException("Error creating inventory line. Cause: " + e.getLocalizedMessage());
			}
		}
	} // createRelatedInventoryLines

	/**
	 * Delete related inventory lines when a master line is deleted or its product is modified.
	 * Only applicable for physical inventory and internal use inventory document sub-types.
	 *
	 * @param inventoryLine the master inventory line
	 * @param isChanged     true when called from a change event, false when the master line is deleted
	 * @return false if no deletion was needed, true otherwise
	 */
	public static boolean deleteRelatedInventoryLines(MInventoryLine inventoryLine, boolean isChanged) {

		if (!isSupportedInventoryType(inventoryLine.getParent()))
			return true;

		MProduct product;

		if (isChanged && inventoryLine.is_ValueChanged(MInventoryLine.COLUMNNAME_M_Product_ID)) {
			int previousProductId = (Integer) inventoryLine.get_ValueOld(MInventoryLine.COLUMNNAME_M_Product_ID);
			product = MProduct.get(previousProductId);
		} else {
			product = inventoryLine.getProduct();
		}

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {

			MInventory inventory = inventoryLine.getParent();
			if (log.isLoggable(Level.INFO))
				log.info("Deleting related lines for: " + product.getName() + " in inventory: " + inventory.get_ID());

			// If the change is made when the document is completed don't do anything
			if (isChanged && !inventoryLine.is_ValueChanged(MInventoryLine.COLUMNNAME_QtyEntered)
					&& !inventoryLine.is_ValueChanged(MInventoryLine.COLUMNNAME_M_Product_ID))
				return false;

			for (MInventoryLine line : inventory.getLines(false)) {
				if (Objects.equals(line.get_Value(RelatedProductConstants.MasterInventoryLine_COLUMN_NAME), inventoryLine.get_ID())) {
					line.set_ValueOfColumn(RelatedProductConstants.MasterInventoryLine_COLUMN_NAME, null);
					line.deleteEx(true, inventory.get_TrxName());
				}
			}
		}
		return true;
	} // deleteRelatedInventoryLines

	/**
	 * Clears the master inventory line reference on all supplemental lines of the given inventory.
	 * This is required before deleting an unprocessed inventory to remove the foreign key
	 * constraint that would otherwise prevent deletion.
	 * Only applicable for physical inventory and internal use inventory document sub-types.
	 *
	 * @param inventory the inventory whose supplemental line references should be cleared
	 */
	public static void clearMasterInventoryLineReferences(MInventory inventory) {
		if (!inventory.isProcessed() && isSupportedInventoryType(inventory)) {
			for (MInventoryLine line : inventory.getLines(false)) {
				line.set_ValueOfColumn(RelatedProductConstants.MasterInventoryLine_COLUMN_NAME, null);
				line.saveEx();
			}
		}
	} // clearMasterInventoryLineReferences

	/**
	 * Returns true if the inventory document sub-type supports related product lines.
	 * Only {@code PhysicalInventory} and {@code InternalUseInventory} are supported;
	 * {@code CostAdjustment} is excluded.
	 *
	 * @param inventory the inventory document to check
	 * @return true if related product processing should be applied
	 */
	private static boolean isSupportedInventoryType(MInventory inventory) {
		String subType = MDocType.get(inventory.getCtx(), inventory.getC_DocType_ID()).getDocSubTypeInv();
		return MDocType.DOCSUBTYPEINV_PhysicalInventory.equals(subType)
				|| MDocType.DOCSUBTYPEINV_InternalUseInventory.equals(subType);
	}

	/**
	 * Updates the quantity of related inventory lines when the master line quantity changes.
	 *
	 * @param inventoryLine the master inventory line whose quantity was changed
	 */
	public static void updateRelatedLinesQty(MInventoryLine inventoryLine) {

		MInventory inventory = inventoryLine.getParent();

		if (!isSupportedInventoryType(inventory))
			return;

		MProduct product = inventoryLine.getProduct();

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {
			if (log.isLoggable(Level.INFO))
				log.info("Modifying related products for: " + product.getName() + " in inventory: " + inventory.get_ID());

			for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {
				if (relatedProduct.isConditionalUOM(inventoryLine.getC_UOM_ID())) {
					for (MInventoryLine relatedLine : inventory.getLines(false)) {
						if (isRelatedLine(relatedLine, inventoryLine, relatedProduct)) {

							BigDecimal qty = relatedProduct.get_ValueAsInt("Qty") != 0
									? BigDecimal.valueOf(relatedProduct.get_ValueAsInt("Qty")).multiply(inventoryLine.getQtyEntered())
									: BigDecimal.ONE;
							relatedLine.setQtyEntered(qty);
							relatedLine.saveEx(inventory.get_TrxName());
						}
					}
				}
			}
		}
	} // updateRelatedLinesQty

	/**
	 * @return true if the line is a related line of the master line for the given related product
	 */
	private static boolean isRelatedLine(MInventoryLine relatedLine, MInventoryLine masterLine, MRelatedProduct relatedProduct) {
		return Objects.equals(relatedLine.get_Value(RelatedProductConstants.MasterInventoryLine_COLUMN_NAME), masterLine.get_ID())
				&& relatedLine.getM_Product_ID() == relatedProduct.getRelatedProduct_ID();
	}

}

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
import org.compiere.model.MMovement;
import org.compiere.model.MMovementLine;
import org.compiere.model.MProduct;
import org.compiere.util.CLogger;

import de.bxservice.model.MRelatedProduct;

public class RelatedMovementHandler {

	private static CLogger log = CLogger.getCLogger(RelatedMovementHandler.class);

	/**
	 * Create new lines for related products on a movement line.
	 *
	 * @param movementLine the master movement line that triggered the event
	 * @param type         the event topic (PO_AFTER_NEW or PO_AFTER_CHANGE)
	 */
	public static void createRelatedMovementLines(MMovementLine movementLine, String type) {

		MMovement movement = movementLine.getParent();
		MProduct product = movementLine.getProduct();

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {
			try {
				if (log.isLoggable(Level.INFO))
					log.info("Creating related products for: " + product.getName() + " in movement: " + movement.get_ID());

				int lineNo = movementLine.getLine();

				// If the record was modified delete previous related lines to avoid duplicates
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)) {
					if (!deleteRelatedMovementLines(movementLine, true))
						return;
				}

				for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {
					if (relatedProduct.isConditionalUOM(movementLine.getC_UOM_ID())) {

						MMovementLine newLine = new MMovementLine(movement);

						newLine.setLine(++lineNo);
						newLine.setM_Product_ID(relatedProduct.getRelatedProduct_ID());
						newLine.setM_Locator_ID(movementLine.getM_Locator_ID());
						newLine.setM_LocatorTo_ID(movementLine.getM_LocatorTo_ID());

						BigDecimal qty = relatedProduct.get_ValueAsInt("Qty") != 0
								? BigDecimal.valueOf(relatedProduct.get_ValueAsInt("Qty")).multiply(movementLine.getMovementQty())
								: BigDecimal.ONE;
						newLine.setMovementQty(qty);

						if (relatedProduct.getDescription() != null)
							newLine.setDescription(relatedProduct.getDescription());

						newLine.set_ValueOfColumn(RelatedProductConstants.MasterMovementLine_COLUMN_NAME, movementLine.get_ID());
						newLine.saveEx(movement.get_TrxName());

						if (log.isLoggable(Level.INFO))
							log.info("A new movement line was added with product: " + relatedProduct.getRelatedProduct_ID());
					}
				}
			} catch (Exception e) {
				throw new AdempiereException("Error creating movement line. Cause: " + e.getLocalizedMessage());
			}
		}
	} // createRelatedMovementLines

	/**
	 * Delete related movement lines when a master line is deleted or its product is modified.
	 *
	 * @param movementLine the master movement line
	 * @param isChanged    true when called from a change event, false when the master line is deleted
	 * @return false if no deletion was needed, true otherwise
	 */
	public static boolean deleteRelatedMovementLines(MMovementLine movementLine, boolean isChanged) {

		MProduct product;

		if (isChanged && movementLine.is_ValueChanged(MMovementLine.COLUMNNAME_M_Product_ID)) {
			int previousProductId = (Integer) movementLine.get_ValueOld(MMovementLine.COLUMNNAME_M_Product_ID);
			product = MProduct.get(previousProductId);
		} else {
			product = movementLine.getProduct();
		}

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {

			MMovement movement = movementLine.getParent();
			if (log.isLoggable(Level.INFO))
				log.info("Deleting related lines for: " + product.getName() + " in movement: " + movement.get_ID());

			// If the change is made when the document is completed don't do anything
			if (isChanged && !movementLine.is_ValueChanged(MMovementLine.COLUMNNAME_MovementQty)
					&& !movementLine.is_ValueChanged(MMovementLine.COLUMNNAME_M_Product_ID))
				return false;

			for (MMovementLine line : movement.getLines(false)) {
				if (Objects.equals(line.get_Value(RelatedProductConstants.MasterMovementLine_COLUMN_NAME), movementLine.get_ID())) {
					line.set_ValueOfColumn(RelatedProductConstants.MasterMovementLine_COLUMN_NAME, null);
					line.deleteEx(true, movement.get_TrxName());
				}
			}
		}
		return true;
	} // deleteRelatedMovementLines

	/**
	 * Clears the master movement line reference on all supplemental lines of the given movement.
	 * This is required before deleting an unprocessed movement to remove the foreign key
	 * constraint that would otherwise prevent deletion.
	 *
	 * @param movement the movement whose supplemental line references should be cleared
	 */
	public static void clearMasterMovementLineReferences(MMovement movement) {
		if (!movement.isProcessed()) {
			for (MMovementLine line : movement.getLines(false)) {
				line.set_ValueOfColumn(RelatedProductConstants.MasterMovementLine_COLUMN_NAME, null);
				line.saveEx();
			}
		}
	} // clearMasterMovementLineReferences

	/**
	 * Updates the quantity of related movement lines when the master line quantity changes.
	 *
	 * @param movementLine the master movement line whose quantity was changed
	 */
	public static void updateRelatedLinesQty(MMovementLine movementLine) {

		MMovement movement = movementLine.getParent();
		MProduct product = movementLine.getProduct();

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {
			if (log.isLoggable(Level.INFO))
				log.info("Modifying related products for: " + product.getName() + " in movement: " + movement.get_ID());

			for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {
				if (relatedProduct.isConditionalUOM(movementLine.getC_UOM_ID())) {
					for (MMovementLine relatedLine : movement.getLines(false)) {
						if (isRelatedLine(relatedLine, movementLine, relatedProduct)) {

							BigDecimal qty = relatedProduct.get_ValueAsInt("Qty") != 0
									? BigDecimal.valueOf(relatedProduct.get_ValueAsInt("Qty")).multiply(movementLine.getMovementQty())
									: BigDecimal.ONE;
							relatedLine.setMovementQty(qty);
							relatedLine.saveEx(movement.get_TrxName());
						}
					}
				}
			}
		}
	} // updateRelatedLinesQty

	/**
	 * @return true if the line is a related line of the master line for the given related product
	 */
	private static boolean isRelatedLine(MMovementLine relatedLine, MMovementLine masterLine, MRelatedProduct relatedProduct) {
		return Objects.equals(relatedLine.get_Value(RelatedProductConstants.MasterMovementLine_COLUMN_NAME), masterLine.get_ID())
				&& relatedLine.getM_Product_ID() == relatedProduct.getRelatedProduct_ID();
	}

}

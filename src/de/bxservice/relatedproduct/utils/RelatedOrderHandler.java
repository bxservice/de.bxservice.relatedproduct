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
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MProduct;
import org.compiere.model.PO;
import org.compiere.util.CLogger;

import de.bxservice.model.MRelatedProduct;

public class RelatedOrderHandler {
	
	private static CLogger log = CLogger.getCLogger(RelatedOrderHandler.class);

	/**
	 * Create new lines for related products
	 * @param orderLine
	 * @param type
	 */
	public static void createSupplementalOrderLines(MOrderLine orderLine, String type) {

		MOrder order = orderLine.getParent();
		MProduct product = orderLine.getProduct();
		
		if (product != null && order.getC_POS_ID() == 0 
				&& MRelatedProduct.hasRelatedProducts(product)) {
			try {
				if (log.isLoggable(Level.INFO))
					log.info("Creating related products for: " + product.getName() + " in order: " + order.get_ID());

				int lineNo = orderLine.getLine();

				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)) {
					if(!deleteRelatedOrderLines(orderLine, true))
						return;
				}

				for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {
					if (relatedProduct.isConditionalUOM(orderLine.getC_UOM_ID())) {

						MOrderLine newLine = new MOrderLine(order);

						newLine.setLine(++lineNo);
						newLine.setM_Product_ID(relatedProduct.getRelatedProduct_ID(), true);
						
						if (relatedProduct.get_ValueAsInt("Qty") != 0)
							newLine.setQty(BigDecimal.valueOf(relatedProduct.get_ValueAsInt("Qty")).multiply(orderLine.getQtyEntered()));
						else
							newLine.setQty(BigDecimal.valueOf(1));
						
						if (relatedProduct.getDescription() != null)
							newLine.setDescription(relatedProduct.getDescription());

						newLine.setPrice();
						newLine.set_ValueOfColumn(RelatedProductConstants.MasterOrderLine_COLUMN_NAME, orderLine.get_ID());
						newLine.saveEx(order.get_TrxName());

						if (log.isLoggable(Level.INFO))
							log.info("A new sales order line was added with product: " + relatedProduct.getRelatedProduct_ID());
					}
				}
			} catch (Exception e) {
				throw new AdempiereException("Error creating order line. Cause: " + e.getLocalizedMessage());
			}
		}
	} //createSupplementalOrderLines
	
	/**
	 * Delete related order lines when a master product is deleted or modified
	 * @param orderLine
	 * @param isChanged
	 * @return
	 */
	public static boolean deleteRelatedOrderLines(MOrderLine orderLine, boolean isChanged) {

		MProduct product;
		
		//If the product is changed in the master line, delete old related product lines
		if (isChanged && orderLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID)) {
			int previousProductId = (Integer) orderLine.get_ValueOld(MOrderLine.COLUMNNAME_M_Product_ID);
			product = MProduct.get(previousProductId);
		} else {
			product = orderLine.getProduct();
		}
		
		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {
			
			MOrder order = orderLine.getParent();
			if (log.isLoggable(Level.INFO))
				log.info("Deleting related lines for: " + product.getName() + " in order: " + order.get_ID());

			//If the change is made when the document is completed don't do anything
			if (isChanged && !orderLine.is_ValueChanged(MOrderLine.COLUMNNAME_QtyEntered) &&
					!orderLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID))
				return false;

			for (MOrderLine line : order.getLines()) {
				if (line.get_Value(RelatedProductConstants.MasterOrderLine_COLUMN_NAME) != null && 
						line.get_Value(RelatedProductConstants.MasterOrderLine_COLUMN_NAME).equals(orderLine.get_ID())) {
					line.set_ValueOfColumn(RelatedProductConstants.MasterOrderLine_COLUMN_NAME, null);   //Allows delete when master is deleted
					line.deleteEx(true, order.get_TrxName());
				}
			}
		}
		return true;
	} 
	
	public static void updateRelatedLinesQty(MOrderLine orderLine) {

		MOrder order = orderLine.getParent();
		if (isPOClosing(order))
			return;
		
		MProduct product = orderLine.getProduct();

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {
			if (log.isLoggable(Level.INFO))
				log.info("Modifying related products for: " + product.getName() + " in order: " + order.get_ID());
			
			for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {

				if (relatedProduct.isConditionalUOM(orderLine.getC_UOM_ID())) {

					for (MOrderLine relatedLine : order.getLines()) {
						if (isRelatedLine(relatedLine, orderLine, relatedProduct)) {
							int relatedProductQty = relatedProduct.get_ValueAsInt("Qty"); 
							
							if (relatedProductQty != 0) {
								relatedLine.setQty(BigDecimal.valueOf(relatedProductQty).multiply(orderLine.getQtyEntered()));
							} else
								relatedLine.setQty(BigDecimal.valueOf(1));						

							relatedLine.saveEx(order.get_TrxName());
						}
					}
				}
			}

		}
	} 
	
	public static void setPOClosingAttribute(PO po, Object value) {
		po.set_Attribute(RelatedProductConstants.PO_CLOSING_ATTRIBUTE_NAME, value);
	}
	
	private static boolean isPOClosing(PO po) {
	    return Boolean.TRUE.equals(po.get_Attribute(RelatedProductConstants.PO_CLOSING_ATTRIBUTE_NAME));
	}

	/**
	 * 
	 * @param relatedLine
	 * @return true if the line is a related line of the master line with the related product
	 */
	private static boolean isRelatedLine(MOrderLine relatedLine, MOrderLine masterLine, MRelatedProduct relatedProduct) {
		return Objects.equals(relatedLine.get_Value(RelatedProductConstants.MasterOrderLine_COLUMN_NAME), masterLine.get_ID()) 
				&& relatedLine.getM_Product_ID() == relatedProduct.getRelatedProduct_ID();
	}
	
	/**
	 * Clears the master order line reference on all supplemental lines of the given order.
	 * This is required before deleting an unprocessed order to remove the foreign key
	 * constraint that would otherwise prevent deletion.
	 *
	 * @param order the order whose supplemental line references should be cleared
	 */
	public static void clearMasterOrderLineReferences(MOrder order) {
		if (!order.isProcessed()) {
			for (MOrderLine line : order.getLines()) {
				line.set_ValueOfColumn(RelatedProductConstants.MasterOrderLine_COLUMN_NAME, null);
				line.saveEx();
			}
		}
	} //allowOrderDeletion

}

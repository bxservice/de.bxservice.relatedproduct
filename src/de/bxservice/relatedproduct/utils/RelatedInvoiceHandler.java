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
import java.util.logging.Level;

import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrderLine;
import org.compiere.model.MProduct;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;

import de.bxservice.model.MRelatedProduct;

public class RelatedInvoiceHandler {
	
	private static CLogger log = CLogger.getCLogger(RelatedInvoiceHandler.class);

	/**
	 * Create new lines for related products
	 * @param invoiceLine
	 * @param type
	 */
	public static void createSupplementalInvoiceLines(MInvoiceLine invoiceLine, String type) {

		MInvoice invoice = MInvoice.get(invoiceLine.getC_Invoice_ID());
		MProduct product = MProduct.get(invoiceLine.getM_Product_ID());
		
		if (product != null 
			&& MRelatedProduct.hasRelatedProducts(product)
			&& (invoiceLine.getM_InOutLine_ID() == 0 || !invoice.isSOTrx())
			&& !Env.getContext(Env.getCtx(), RelatedProductConstants.REVERSAL_CONTEXT_KEY).equals(invoice.get_TrxName())) {
			try {
				if (log.isLoggable(Level.INFO))
					log.info("Creating related products for: "+product.getName() + " in invoice: " + invoice.get_ID());

				int lineNo = invoiceLine.getLine();

				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)) {
						if (!deleteRelatedInvoiceLines(invoiceLine, true))
							return;
				}

				for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {

					if (relatedProduct.isConditionalUOM(invoiceLine.getC_UOM_ID())) {

						MInvoiceLine newLine = new MInvoiceLine(invoice);

						newLine.setLine(++lineNo);
						newLine.setM_Product_ID(relatedProduct.getRelatedProduct_ID(), true);
						if (relatedProduct.get_ValueAsInt("Qty") != 0)
							newLine.setQty(BigDecimal.valueOf(relatedProduct.get_ValueAsInt("Qty")).multiply(invoiceLine.getQtyEntered()));
						else
							newLine.setQty(BigDecimal.ONE);

						if (relatedProduct.getDescription() != null)
							newLine.setDescription(relatedProduct.getDescription());

						newLine.setPrice();
						newLine.set_ValueOfColumn(RelatedProductConstants.MasterInvoiceLine_COLUMN_NAME, invoiceLine.get_ID());

						// assign purchase order line
						if (!invoice.isSOTrx() && invoiceLine.getC_OrderLine_ID() > 0) {
							String sql = "SELECT C_OrderLine_ID FROM C_OrderLine WHERE BAY_MasterOrderLine_ID=? AND M_Product_ID=? AND QtyOrdered=?";
							int orderLineId = DB.getSQLValueEx(invoiceLine.get_TrxName(), sql,
									invoiceLine.getC_OrderLine_ID(),
									newLine.getM_Product_ID(),
									newLine.getQtyInvoiced());

							newLine.setC_OrderLine_ID(orderLineId);
						}
						newLine.saveEx(invoice.get_TrxName());
						
						if (log.isLoggable(Level.INFO))
							log.info("A new invoice line was added with product: " + relatedProduct.getRelatedProduct_ID());
					}
				}
			} catch (Exception e) {
				throw new AdempiereException("Error creating invoice line. Cause: " + e.getLocalizedMessage());
			}
		}

	} //createSupplementalInvoiceLines
	
	/**
	 * Delete related invoice lines when a master product is deleted or modified
	 * @param invoiceLine
	 * @param isChanged
	 * @return
	 */
	public static boolean deleteRelatedInvoiceLines(MInvoiceLine invoiceLine, boolean isChanged) {
		MProduct product;
		
		if (isChanged && invoiceLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID)) {
			int previousProductId = (Integer) invoiceLine.get_ValueOld(MOrderLine.COLUMNNAME_M_Product_ID);
			product = MProduct.get(previousProductId);
		} else {
			product = invoiceLine.getProduct();
		}

		if (product != null && MRelatedProduct.hasRelatedProducts(product)) {
			
			MInvoice invoice = invoiceLine.getParent();
			if (log.isLoggable(Level.INFO))
				log.info("Creating related lines for: " + product.getName() + " in invoice: " + invoice.get_ID());
			
			//If the change is made when the document is completed don't do anything
			if (isChanged && !invoiceLine.is_ValueChanged(MOrderLine.COLUMNNAME_QtyEntered) &&
					!invoiceLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID))
				return false;

			for (MInvoiceLine line :invoice.getLines()) {
				if (line.get_Value(RelatedProductConstants.MasterInvoiceLine_COLUMN_NAME) != null && 
						line.get_Value(RelatedProductConstants.MasterInvoiceLine_COLUMN_NAME).equals(invoiceLine.get_ID())) {
					line.set_ValueOfColumn(RelatedProductConstants.MasterInvoiceLine_COLUMN_NAME, null); //Allows delete when master is deleted
					line.deleteEx(true, invoice.get_TrxName());
				}
			}
		}
		return true;
	} 
	
	/**
	 * Clears the master invoice line reference on all supplemental lines of the given order.
	 * This is required before deleting an unprocessed invoice to remove the foreign key
	 * constraint that would otherwise prevent deletion.
	 *
	 * @param invoice the invoice whose supplemental line references should be cleared
	 */
	public static void clearMasterInvoiceLineReferences(MInvoice invoice) {
		if (!invoice.isProcessed()) {
			for (MInvoiceLine line : invoice.getLines()) {
				line.set_ValueOfColumn(RelatedProductConstants.MasterInvoiceLine_COLUMN_NAME, null);
				line.saveEx();
			}
		}
	} 
	
	public static void setMasterInvoiceLineReferences(MInvoice invoice) {
		MInvoiceLine[] invoiceLines = invoice.getLines();
		for (MInvoiceLine invoiceLine : invoiceLines) {
			MOrderLine referencedOrderLine = new MOrderLine(Env.getCtx(), invoiceLine.getC_OrderLine_ID(), invoice.get_TrxName());
			int masterOrderLineID = referencedOrderLine.get_ValueAsInt(RelatedProductConstants.MasterOrderLine_COLUMN_NAME);
			if (masterOrderLineID > 0) {
				int masterInvoiceLineID = getMasterInvoiceLineIDFromOrderLine(invoiceLines, masterOrderLineID);
				invoiceLine.set_ValueOfColumn(RelatedProductConstants.MasterInvoiceLine_COLUMN_NAME, masterInvoiceLineID);
				invoiceLine.saveEx(invoice.get_TrxName());
			}
		}
	}
	
	private static int getMasterInvoiceLineIDFromOrderLine(MInvoiceLine[] invoiceLines, int orderLineID) {
		for (MInvoiceLine invoiceLine : invoiceLines) {
			if (invoiceLine.getC_OrderLine_ID() == orderLineID) {
				return invoiceLine.getC_InvoiceLine_ID();
			}
		}
		return -1;
	}
}

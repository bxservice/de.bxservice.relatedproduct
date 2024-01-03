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

package de.bxservice.model;

import java.math.BigDecimal;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MProduct;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.osgi.service.event.Event;

public class ValidatorRelatedProduct extends AbstractEventHandler{

	/**	Logger			*/
	private static CLogger log = CLogger.getCLogger(ValidatorRelatedProduct.class);
	private static final String REVERSAL_CONTEXT_KEY = "relatedTrxNameReversal";
	private static final String MasterOrderLine_COLUMN_NAME = "Bay_MasterOrderLine_ID";
	private static final String MasterInvoiceLine_COLUMN_NAME = "Bay_MasterInvoiceLine_ID";
	private static final String PO_CLOSING_ATTRIBUTE_NAME = "Bay_IsClosing";
	
	@Override
	protected void initialize() {
		log.warning("");
		
		//Invoice (Customer/Vendor)
		registerTableEvent(IEventTopics.PO_BEFORE_DELETE, MInvoice.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_NEW, MInvoiceLine.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_CHANGE, MInvoiceLine.Table_Name);
		registerTableEvent(IEventTopics.PO_BEFORE_DELETE, MInvoiceLine.Table_Name);
		registerTableEvent(IEventTopics.DOC_BEFORE_REVERSECORRECT, MInvoice.Table_Name);
		registerTableEvent(IEventTopics.DOC_BEFORE_REVERSEACCRUAL, MInvoice.Table_Name);
		registerTableEvent(IEventTopics.DOC_AFTER_REVERSEACCRUAL, MInvoice.Table_Name);
		registerTableEvent(IEventTopics.DOC_AFTER_REVERSECORRECT, MInvoice.Table_Name);
		registerTableEvent(IEventTopics.DOC_BEFORE_PREPARE, MInvoice.Table_Name);

		//Sales Order / Purchase Order
		registerTableEvent(IEventTopics.DOC_BEFORE_CLOSE, MOrder.Table_Name);
		registerTableEvent(IEventTopics.PO_BEFORE_DELETE, MOrder.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_NEW, MOrderLine.Table_Name);
		registerTableEvent(IEventTopics.PO_AFTER_CHANGE, MOrderLine.Table_Name);
		registerTableEvent(IEventTopics.PO_BEFORE_DELETE, MOrderLine.Table_Name);

	} //initialize

	@Override
	protected void doHandleEvent(Event event) {
		String type = event.getTopic();
		PO po = getPO(event);
		log.info(po.get_TableName() + " Type: "+type);

		// Model Events
		if (po instanceof MOrderLine &&
				type.equals(IEventTopics.PO_AFTER_CHANGE) &&
				po.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID)) {

			createSupplementalOrderLines((MOrderLine)po, type);
		}
		else if (po instanceof MOrderLine &&
				type.equals(IEventTopics.PO_AFTER_CHANGE) &&
				po.is_ValueChanged(MOrderLine.COLUMNNAME_QtyOrdered)) {
			updateRelatedLinesQty((MOrderLine)po);
		}
		else if (po instanceof MInvoiceLine && 
				(type.equals(IEventTopics.PO_AFTER_NEW) || 
						type.equals(IEventTopics.PO_AFTER_CHANGE))) {

			createSupplementalInvoiceLines((MInvoiceLine)po, type);

		}
		else if (po instanceof MOrderLine && type.equals(IEventTopics.PO_AFTER_NEW)) {
			createSupplementalOrderLines((MOrderLine)po, type);
		}
		else if ((po instanceof MOrderLine || po instanceof MInvoiceLine) 
				&& type.equals(IEventTopics.PO_BEFORE_DELETE)) {
			nonDeleteSupplementalLines(po);
		}
		else if ((po instanceof MOrder || po instanceof MInvoice) 
				&& type.equals(IEventTopics.PO_BEFORE_DELETE)) {
			allowDeletion(po);
		}
		else if (po instanceof MInvoice && 
				(type.equals(IEventTopics.DOC_BEFORE_REVERSECORRECT) || 
				type.equals(IEventTopics.DOC_BEFORE_REVERSEACCRUAL))) {
			Env.setContext(Env.getCtx(), REVERSAL_CONTEXT_KEY, po.get_TrxName());
		}
		else if (po instanceof MInvoice && 
				(type.equals(IEventTopics.DOC_AFTER_REVERSEACCRUAL) || 
				type.equals(IEventTopics.DOC_AFTER_REVERSECORRECT))) {
			Env.setContext(Env.getCtx(), REVERSAL_CONTEXT_KEY, "");
		}
		else if (po instanceof MInvoice && 
				(type.equals(IEventTopics.DOC_BEFORE_PREPARE))) {
			setMasterInvoiceLineReferences((MInvoice) po);
		}
		else if (po instanceof MOrder && 
				(type.equals(IEventTopics.DOC_BEFORE_CLOSE))) {
			setPOClosingAttribute(po, true);
		}
	} //doHandleEvent
	
	/**
	 * When the master document is delete, remove the constraints to be able to delete it
	 * @param po
	 */
	private void allowDeletion(PO po) {
		if (po instanceof MOrder)
			allowOrderDeletion((MOrder)po);

		else if (po instanceof MInvoice)
			allowInvoiceDeletion((MInvoice)po);
		
	} //allowDeletion

	private void allowInvoiceDeletion(MInvoice invoice) {
		if (!invoice.isProcessed()) {
			for (MInvoiceLine line : invoice.getLines()) {
				line.set_ValueOfColumn(MasterInvoiceLine_COLUMN_NAME, null);   //Allows delete when master is deleted
				line.saveEx();
			}
		}
	} //allowInvoiceDeletion

	private void allowOrderDeletion(MOrder order) {

		if (!order.isProcessed()) {
			for (MOrderLine line : order.getLines()) {
				line.set_ValueOfColumn(MasterOrderLine_COLUMN_NAME, null);   //Allows delete when master is deleted
				line.saveEx();
			}
		}
	} //allowOrderDeletion

	/**
	 * When the master product is changed, delete old related product lines
	 * @param po
	 */
	private boolean removePreviousRelated(PO po) {
		
		if (po instanceof MOrderLine)
			return deleteSupplementaOrderLines((MOrderLine)po, true);

		else if (po instanceof MInvoiceLine)
			return deleteSupplementaInvoiceLines((MInvoiceLine)po, true);
		
		return false;
		
	} //removePreviousRelated

	/**
	 * Delete related lines when the parent is deleted.
	 * @param po
	 */
	private void deleteSupplementalLines(PO po) {

		if (po instanceof MOrderLine)
			deleteSupplementaOrderLines((MOrderLine)po, false);

		else if (po instanceof MInvoiceLine)
			deleteSupplementaInvoiceLines((MInvoiceLine)po, false);
	
	}//deleteSupplementalLines
	
	/**
	 * Delete related order lines when a master product is deleted or modified
	 * @param orderLine
	 * @param isChanged
	 * @return
	 */
	private boolean deleteSupplementaOrderLines(MOrderLine orderLine, boolean isChanged) {

		MProduct product;
		
		//If the product is changed in the master line, delete old related product lines
		if (isChanged && orderLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID)) {
			int previousProductId = (Integer) orderLine.get_ValueOld(MOrderLine.COLUMNNAME_M_Product_ID);
			product = MProduct.get(previousProductId);
		} else
			product = orderLine.getProduct();
		
		if (product != null && hasRelatedProducts(product)) {
			
			MOrder order = orderLine.getParent();
			log.info("Deleting related lines for: " + product.getName() + " in order: " + order.get_ID());

			//If the change is made when the document is completed don't do anything
			if (isChanged && !orderLine.is_ValueChanged(MOrderLine.COLUMNNAME_QtyEntered) &&
					!orderLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID))
				return false;
			for (MOrderLine line : order.getLines()) {
				if (line.get_Value(MasterOrderLine_COLUMN_NAME) != null && 
						line.get_Value(MasterOrderLine_COLUMN_NAME).equals(orderLine.get_ID())) {
					line.set_ValueOfColumn(MasterOrderLine_COLUMN_NAME, null);   //Allows delete when master is deleted
					line.deleteEx(true, order.get_TrxName());
				}
			}

		}
		return true;
	} //deleteSupplementaOrderLines
	
	/**
	 * Delete related invoice lines when a master product is deleted or modified
	 * @param invoiceLine
	 * @param isChanged
	 * @return
	 */
	private boolean deleteSupplementaInvoiceLines(MInvoiceLine invoiceLine, boolean isChanged) {
		MProduct product;
		
		if (isChanged && invoiceLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID)) {
			int previousProductId = (Integer) invoiceLine.get_ValueOld(MOrderLine.COLUMNNAME_M_Product_ID);
			product = MProduct.get(previousProductId);
		} else
			product = invoiceLine.getProduct();

		if (product != null && hasRelatedProducts(product)) {
			
			MInvoice invoice = invoiceLine.getParent();
			log.info("Creating related lines for: " + product.getName() + " in invoice: " + invoice.get_ID());
			
			//If the change is made when the document is completed don't do anything
			if (isChanged && !invoiceLine.is_ValueChanged(MOrderLine.COLUMNNAME_QtyEntered) &&
					!invoiceLine.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID))
				return false;
			for (MInvoiceLine line :invoice.getLines()) {
				if (line.get_Value(MasterInvoiceLine_COLUMN_NAME) != null && 
						line.get_Value(MasterInvoiceLine_COLUMN_NAME).equals(invoiceLine.get_ID())) {
					line.set_ValueOfColumn(MasterInvoiceLine_COLUMN_NAME, null); //Allows delete when master is deleted
					line.deleteEx(true, invoice.get_TrxName());
				}
			}
		}
		return true;
	} //deleteSupplementaInvoiceLines
	
	/**
	 * Don't let the supplementary lines be deleted.
	 * Only when the parent is deleted.
	 * @param po
	 */
	private void nonDeleteSupplementalLines(PO po) {
		if ((po instanceof MOrderLine && po.get_Value(MasterOrderLine_COLUMN_NAME) != null) 
				|| (po instanceof MInvoiceLine && po.get_Value(MasterInvoiceLine_COLUMN_NAME) != null))
			throw new AdempiereException(Msg.getMsg(Env.getLanguage(Env.getCtx()), "BAY_SupplementalProducts"));
		else 
			deleteSupplementalLines(po);

	} //nonDeletingSupplementalLines
	
	/**
	 * Create new lines for related products
	 * @param orderLine
	 * @param type
	 */
	private void createSupplementalOrderLines(MOrderLine orderLine, String type) {

		MOrder order = orderLine.getParent();
		MProduct product = orderLine.getProduct();
		
		if (product != null && order.getC_POS_ID() == 0 && hasRelatedProducts(product)) {
			try {
				log.info("Creating related products for: " + product.getName() + " in order: " + order.get_ID());

				int lineNo = orderLine.getLine();

				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)) {
					if(!removePreviousRelated(orderLine))
						return;
				}

				for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {
					if (isConditionalUOM(relatedProduct, orderLine.getC_UOM_ID())) {

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
						newLine.set_ValueOfColumn(MasterOrderLine_COLUMN_NAME, orderLine.get_ID());
						newLine.saveEx(order.get_TrxName());

						log.info("A new sales order line was added with product: "+relatedProduct.getRelatedProduct().getName());
					}
				}
			} catch (Exception e) {
				throw new AdempiereException("Error creating order line. Cause: " + e.getLocalizedMessage());
			}
		}
	} //createSupplementalOrderLines

	/**
	 * The conditional UOM works to check if the parent product has that UOM the related product is created. 
	 * If it's blank it's always created.
	 * @param relatedProduct
	 * @param lineC_UOM_ID
	 * @return true if a related product must be created based on the UOM of the line 
	 */
	private boolean isConditionalUOM(MRelatedProduct relatedProduct, int lineC_UOM_ID) {
		int conditionalUOM = relatedProduct.get_ValueAsInt("C_UOM_ID");
		return conditionalUOM == 0 || conditionalUOM == lineC_UOM_ID; 
	}

	/**
	 * Create new lines for related products
	 * @param invoiceLine
	 * @param type
	 */
	private void createSupplementalInvoiceLines(MInvoiceLine invoiceLine, String type) {

		MInvoice invoice = invoiceLine.getParent();
		MProduct product = invoiceLine.getProduct();
		
		if (   product != null 
			&& hasRelatedProducts(product)
			&& (invoiceLine.getM_InOutLine_ID() == 0 || !invoice.isSOTrx())
			&& !Env.getContext(Env.getCtx(), REVERSAL_CONTEXT_KEY).equals(invoice.get_TrxName())) {
			try {
				log.info("Creating related products for: "+product.getName() + " in invoice: " + invoice.get_ID());

				int lineNo = invoiceLine.getLine();

				//If the record was modified delete previous supplementary lines to avoid duplicated
				if (type.equals(IEventTopics.PO_AFTER_CHANGE)) {
						if (!removePreviousRelated(invoiceLine))
							return;
				}

				for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {

					if (isConditionalUOM(relatedProduct, invoiceLine.getC_UOM_ID())) {

						MInvoiceLine newLine = new MInvoiceLine(invoice);

						newLine.setLine(++lineNo);
						newLine.setM_Product_ID(relatedProduct.getRelatedProduct_ID(), true);
						if (relatedProduct.get_ValueAsInt("Qty") != 0)
							newLine.setQty(BigDecimal.valueOf(relatedProduct.get_ValueAsInt("Qty")).multiply(invoiceLine.getQtyEntered()));
						else
							newLine.setQty(BigDecimal.valueOf(1));
						if (relatedProduct.getDescription() != null)
							newLine.setDescription(relatedProduct.getDescription());
						newLine.setPrice();
						newLine.set_ValueOfColumn(MasterInvoiceLine_COLUMN_NAME, invoiceLine.get_ID());
						// assign purchase order line
						if (! invoice.isSOTrx() && invoiceLine.getC_OrderLine_ID()>0) {
							String sql = "SELECT C_OrderLine_ID FROM C_OrderLine WHERE BAY_MasterOrderLine_ID=? AND M_Product_ID=? AND QtyOrdered=?";
							int orderLineId = DB.getSQLValueEx(invoiceLine.get_TrxName(), sql,
									invoiceLine.getC_OrderLine_ID(),
									newLine.getM_Product_ID(),
									newLine.getQtyInvoiced());
							newLine.setC_OrderLine_ID(orderLineId);
						}
						newLine.saveEx(invoice.get_TrxName());

						log.info("A new invoice line was added with product: " + relatedProduct.getRelatedProduct().getName());
					}
				}
			} catch (Exception e) {
				throw new AdempiereException("Error creating invoice line. Cause: " + e.getLocalizedMessage());
			}
		}

	} //createSupplementalInvoiceLines
	
	private void updateRelatedLinesQty(MOrderLine orderLine) {
		MOrder order = orderLine.getParent();
		if (isPOClosing(order))
			return;

		
		MProduct product = orderLine.getProduct();

		if (product != null && hasRelatedProducts(product)) {
			log.info("Modifying related products for: " + product.getName() + " in order: " + order.get_ID());
			
			for (MRelatedProduct relatedProduct : MRelatedProduct.getRelatedLines(product)) {

				if (isConditionalUOM(relatedProduct, orderLine.getC_UOM_ID())) {

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
	} //createSupplementalInvoiceLines

	private boolean hasRelatedProducts(MProduct product) {
		if (MRelatedProduct.getRelatedLines(product) == null || MRelatedProduct.getRelatedLines(product).size()==0)
			return false;
		return true;
	} //hasRelatedProducts
	
	/**
	 * 
	 * @param relatedLine
	 * @return true if the line is a related line of the master line with the related product
	 */
	private boolean isRelatedLine(MOrderLine relatedLine, MOrderLine masterLine, MRelatedProduct relatedProduct) {
		return relatedLine.get_Value(MasterOrderLine_COLUMN_NAME) != null && 
				relatedLine.get_Value(MasterOrderLine_COLUMN_NAME).equals(masterLine.get_ID()) && 
				relatedLine.getM_Product_ID() == relatedProduct.getRelatedProduct_ID();
	}
	
	private void setMasterInvoiceLineReferences(MInvoice invoice) {
		MInvoiceLine[] invoiceLines = invoice.getLines();
		for (MInvoiceLine invoiceLine : invoiceLines) {
			MOrderLine referencedOrderLine = new MOrderLine(Env.getCtx(), invoiceLine.getC_OrderLine_ID(), invoice.get_TrxName());
			int masterOrderLineID = referencedOrderLine.get_ValueAsInt(MasterOrderLine_COLUMN_NAME);
			if (masterOrderLineID > 0) {
				int masterInvoiceLineID = getMasterInvoiceLineIDFromOrderLine(invoiceLines, masterOrderLineID);
				invoiceLine.set_ValueOfColumn(MasterInvoiceLine_COLUMN_NAME, masterInvoiceLineID);
				invoiceLine.saveEx(invoice.get_TrxName());
			}
		}
	}
	
	private int getMasterInvoiceLineIDFromOrderLine(MInvoiceLine[] invoiceLines, int orderLineID) {
		for (MInvoiceLine invoiceLine : invoiceLines) {
			if (invoiceLine.getC_OrderLine_ID() == orderLineID) {
				return invoiceLine.getC_InvoiceLine_ID();
			}
		}
		return -1;
	}
	
	private void setPOClosingAttribute(PO po, Object value) {
		po.set_Attribute(PO_CLOSING_ATTRIBUTE_NAME, value);
	}
	
	private boolean isPOClosing(PO po) {
		if (po.get_Attribute(PO_CLOSING_ATTRIBUTE_NAME) != null)
			return (boolean) po.get_Attribute(PO_CLOSING_ATTRIBUTE_NAME);

		return false;
	}
}

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

import java.util.logging.Level;

import org.adempiere.base.event.AbstractEventHandler;
import org.adempiere.base.event.IEventTopics;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.PO;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.osgi.service.event.Event;

import de.bxservice.relatedproduct.utils.RelatedInvoiceHandler;
import de.bxservice.relatedproduct.utils.RelatedOrderHandler;
import de.bxservice.relatedproduct.utils.RelatedProductConstants;

public class ValidatorRelatedProduct extends AbstractEventHandler{

	/**	Logger			*/
	private static CLogger log = CLogger.getCLogger(ValidatorRelatedProduct.class);
	
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
		if (log.isLoggable(Level.INFO))
			log.info(po.get_TableName() + " Type: "+type);

		// Model Events
		if (po instanceof MOrderLine orderline &&
			   (type.equals(IEventTopics.PO_AFTER_NEW) ||
				(type.equals(IEventTopics.PO_AFTER_CHANGE) 
						&& po.is_ValueChanged(MOrderLine.COLUMNNAME_M_Product_ID)))) {

			RelatedOrderHandler.createSupplementalOrderLines(orderline, type);
		}
		else if (po instanceof MOrderLine orderline &&
				type.equals(IEventTopics.PO_AFTER_CHANGE) &&
				po.is_ValueChanged(MOrderLine.COLUMNNAME_QtyOrdered)) {

			RelatedOrderHandler.updateRelatedLinesQty(orderline);
		}
		else if (po instanceof MInvoiceLine && 
				(type.equals(IEventTopics.PO_AFTER_NEW) || 
						type.equals(IEventTopics.PO_AFTER_CHANGE))) {

			RelatedInvoiceHandler.createSupplementalInvoiceLines((MInvoiceLine)po, type);
		}
		else if ((po instanceof MOrderLine || po instanceof MInvoiceLine) 
				&& type.equals(IEventTopics.PO_BEFORE_DELETE)) {
			nonDeleteRelatedLines(po);
		}
		else if ((po instanceof MOrder || po instanceof MInvoice) 
				&& type.equals(IEventTopics.PO_BEFORE_DELETE)) {
			allowDeletion(po);
		}
		else if (po instanceof MInvoice && 
				(type.equals(IEventTopics.DOC_BEFORE_REVERSECORRECT) || 
				type.equals(IEventTopics.DOC_BEFORE_REVERSEACCRUAL))) {
			Env.setContext(Env.getCtx(), RelatedProductConstants.REVERSAL_CONTEXT_KEY, po.get_TrxName());
		}
		else if (po instanceof MInvoice && 
				(type.equals(IEventTopics.DOC_AFTER_REVERSEACCRUAL) || 
				type.equals(IEventTopics.DOC_AFTER_REVERSECORRECT))) {
			Env.setContext(Env.getCtx(), RelatedProductConstants.REVERSAL_CONTEXT_KEY, "");
		}
		else if (po instanceof MInvoice && 
				(type.equals(IEventTopics.DOC_BEFORE_PREPARE))) {
			 RelatedInvoiceHandler.setMasterInvoiceLineReferences((MInvoice) po);
		}
		else if (po instanceof MOrder && 
				(type.equals(IEventTopics.DOC_BEFORE_CLOSE))) {
			RelatedOrderHandler.setPOClosingAttribute(po, true);
		}
	} //doHandleEvent
	
	/**
	 * When the master document is delete, remove the constraints to be able to delete it
	 * @param po
	 */
	private void allowDeletion(PO po) {
		if (po instanceof MOrder order)
			RelatedOrderHandler.clearMasterOrderLineReferences(order);

		else if (po instanceof MInvoice invoice)
			RelatedInvoiceHandler.clearMasterInvoiceLineReferences(invoice);
		
	} //allowDeletion


	/**
	 * Delete related lines when the parent is deleted.
	 * @param po
	 */
	private void deleteRelatedLines(PO po) {

		if (po instanceof MOrderLine orderLine)
			RelatedOrderHandler.deleteRelatedOrderLines(orderLine, false);

		else if (po instanceof MInvoiceLine invoiceLine)
			 RelatedInvoiceHandler.deleteRelatedInvoiceLines(invoiceLine, false);
	
	}//deleteSupplementalLines
	
	/**
	 * Don't let the supplementary lines be deleted.
	 * Only when the parent is deleted.
	 * @param po
	 */
	private void nonDeleteRelatedLines(PO po) {
		if ((po instanceof MOrderLine && po.get_Value(RelatedProductConstants.MasterOrderLine_COLUMN_NAME) != null) 
				|| (po instanceof MInvoiceLine && po.get_Value(RelatedProductConstants.MasterInvoiceLine_COLUMN_NAME) != null))
			throw new AdempiereException(Msg.getMsg(Env.getLanguage(Env.getCtx()), "BAY_SupplementalProducts"));
		else 
			deleteRelatedLines(po);
	} //nonDeletingSupplementalLines
	
}

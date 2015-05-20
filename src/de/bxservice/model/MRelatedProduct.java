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

import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;

import org.compiere.model.MProduct;
import org.compiere.model.Query;
import org.compiere.model.X_M_RelatedProduct;

public class MRelatedProduct extends X_M_RelatedProduct{

	private static final long serialVersionUID = -8042349333502294418L;
	
	public MRelatedProduct(Properties ctx, int M_RelatedProduct_ID,
			String trxName) {
		super(ctx, M_RelatedProduct_ID, trxName);
	}

	public MRelatedProduct(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
	}

	/**
	 * 	Get Related Lines for Product
	 *	@param product product
	 *	@return array of MRelatedProduct
	 */
	public static List<MRelatedProduct> getRelatedLines (MProduct product)
	{
		return getRelatedLines(product.getCtx(), product.getM_Product_ID(), product.get_TrxName());
	}	//	getRelatedLines
	
	/**
	 * 	Get MRelatedProduct Lines for Product
	 * 	@param ctx context
	 *	@param M_Product_ID product
	 *	@param trxName transaction
	 *	@return array of Related Products
	 */
	public static List<MRelatedProduct> getRelatedLines (Properties ctx, int M_Product_ID, String trxName)
	{
		final String whereClause = "M_Product_ID=? AND relatedproducttype=?";
		List <MRelatedProduct> list = new Query(ctx, MRelatedProduct.Table_Name, whereClause, trxName)
		.setParameters(new Object[]{M_Product_ID, MRelatedProduct.RELATEDPRODUCTTYPE_Supplemental})
		.setOrderBy("relatedproduct_id")
		.list();

		return list;
	}	//	getRelatedLines
}

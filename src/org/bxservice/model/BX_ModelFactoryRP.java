package org.bxservice.model;

import java.sql.ResultSet;

import org.adempiere.base.IModelFactory;
import org.compiere.model.PO;
import org.compiere.util.Env;

public class BX_ModelFactoryRP implements IModelFactory{

	@Override
	public Class<?> getClass(String tableName) {
		if (MRelatedProduct.Table_Name.equals(tableName))
			return MRelatedProduct.class;
		return null;
	}

	@Override
	public PO getPO(String tableName, int Record_ID, String trxName) {
		if (MRelatedProduct.Table_Name.equals(tableName))
			return new MRelatedProduct(Env.getCtx(), Record_ID, trxName);
		return null;
	}

	@Override
	public PO getPO(String tableName, ResultSet rs, String trxName) {
		if (MRelatedProduct.Table_Name.equals(tableName))
			return new MRelatedProduct(Env.getCtx(), rs, trxName);
		return null;
	}

}

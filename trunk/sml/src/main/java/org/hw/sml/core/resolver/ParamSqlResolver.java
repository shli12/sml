package org.hw.sml.core.resolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hw.sml.FrameworkConstant;
import org.hw.sml.core.resolver.exception.ParamNotConfigException;
import org.hw.sml.core.resolver.exception.ParamNullException;
import org.hw.sml.model.SMLParam;
import org.hw.sml.model.SMLParams;
import org.hw.sml.support.el.El;
import org.hw.sml.tools.RegexUtils;
/**
 * 解析sql获取绑定参数，减少数据库消耗
 * @author hw
 * 时间：2015-08-31
 */
public class ParamSqlResolver implements SqlResolver{

	public Rst resolve(String dialect, String temp,SMLParams sqlParamMaps) {
		List<Object> paramObjects= new ArrayList<Object>();
		List<String> mathers=null;
		//用于绑定参数，时间类处理相对简单，对数据库压力也减少
		mathers=RegexUtils.matchGroup("#\\w+#",temp);
		for(String mather:mathers){
			String property=mather.substring(1, mather.length()-1);
			SMLParam sp=sqlParamMaps.getSmlParam(property);
			if(sp==null){
				throw new ParamNotConfigException(property+" is not configed for param build");
			}
			if(sp.getValue()==null){
				throw new ParamNullException(property+" is  configed  but is null!");
			}
			Object value=sp.getValue();
			if(!(value instanceof Rst)){
				int size=add(paramObjects,value);
				temp=temp.replace(mather,pad(size,"?"));
			}else{
				Rst rst=(Rst)value;
				temp=temp.replace(mather,rst.getSqlString());
				paramObjects.addAll(rst.getParamObjects());
			}
		}
		//用于非绑定参数的增加用于  like 或者  in 之类
		mathers=RegexUtils.matchGroup("\\$\\w+\\$",temp);
		for(String mather:mathers){
			String property=mather.substring(1, mather.length()-1);
			SMLParam sp=sqlParamMaps.getSmlParam(property);
			if(sp==null){
				throw new ParamNotConfigException(property+" is not configed for param build");
			}
			temp=temp.replace(mather, sp.getValue()+"");
		}
		//减少对日志长度的限制，虽然不美观，不过值得
		temp=temp.replace("\n"," ").trim();
		if(Boolean.valueOf(sqlParamMaps.getValue(FrameworkConstant.PARAM_SQLFORMAT,"true").toString())){
				temp=temp.replaceAll("\\s{2,}"," ");
				temp=temp.replaceAll("(?i)where 1=1 and","where");//排除索引的影响
		}
		return new Rst(temp,paramObjects);
	}
	private CharSequence pad(int size, String string) {
		StringBuilder sb=new StringBuilder();
		for(int i=0;i<size;i++){
			sb.append("?");
			if(i<size-1){
				sb.append(",");
			}
		}
		return sb.toString();
	}
	private int add(List<Object> paramObjects,Object value) {
		if(value==null){
			paramObjects.add(value);
			return 1;
		}
		if(value.getClass().isArray()){
			Object[] objs=(Object[]) value;
			paramObjects.addAll(Arrays.asList(objs));
			return objs.length;
		}else{
			if(value.equals("<null>")){
				paramObjects.add(null);
			}else if(value.equals("<empty>")){
				paramObjects.add("");
			}else{
				paramObjects.add(value);
			}
			return 1;
		}
	}
	
	public void setEl(El el) {
		
	}
	
}

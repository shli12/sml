package org.hw.sml.model;

import java.io.Serializable;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import org.hw.sml.core.build.SmlTools;
import org.hw.sml.core.resolver.Rst;
import org.hw.sml.jdbc.JdbcTemplate;
import org.hw.sml.support.SmlAppContextUtils;
import org.hw.sml.support.el.ElException;
import org.hw.sml.support.el.SmlElContext;
import org.hw.sml.support.ioc.BeanHelper;
import org.hw.sml.tools.ClassUtil;
import org.hw.sml.tools.DateTools;
import org.hw.sml.tools.RegexUtils;


public class SMLParam implements Serializable{
	/**
	 * 
	 */
	private static final long serialVersionUID = -5335788865766697965L;
	public SMLParam() {
		super();
	}
	public SMLParam(String name, Object value) {
		super();
		this.name = name;
		this.value = value;
	}
	private String name;
	
	private String type="char";//类型[date,char,number,array,map,list]
	
	private String encode="utf-8";
	private String defaultValue;
	
	private String format;
	
	private Object value;
	
	private int orderIndex;
	
	private String descr;
	
	private Integer enabled=0;
	
	private String split=",";
	
	private String id;
	
	private Map<String,String> doPreParams;
	
	public String getSplit() {
		return split; 
	}

	public void setSplit(String split) {
		this.split = split;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getDefaultValue() {
		
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}

	public Object getValue() {
		return value;
	}

	public void setValue(Object value) {
		this.value = value;
	}

	public int getOrderIndex() {
		return orderIndex;
	}

	public void setOrderIndex(int orderIndex) {
		this.orderIndex = orderIndex;
	}

	public String getDescr() {
		return descr;
	}

	public void setDescr(String descr) {
		this.descr = descr;
	}

	public Integer getEnabled() {
		return enabled;
	}

	public void setEnabled(Integer enabled) {
		this.enabled = enabled;
	}

	public String getFormat() {
		return format;
	}

	public void setFormat(String format) {
		this.format = format;
	}

	public String toString(){
		return name+":"+value;
	}

	public void handlerValue(String value2) {
		if(value2==null){
			return;
		}
		if(getDefaultValue()!=null&&value2.equals(getDefaultValue())){
			if(value2.startsWith("#{")&&value2.endsWith("}")){
				try {
					this.value=BeanHelper.getBean(SmlElContext.class).evel(value2);
				} catch (ElException e) {
					e.printStackTrace();
				}
				return;
			}else if(value2.contains("select ")&&value2.contains(" from ")){
				String[] sqls=value2.split(":",2);
				if(sqls.length==2){
					Rst rst=SmlTools.getRst(sqls[1],doPreParams);
					JdbcTemplate jt=SmlAppContextUtils.getSqlMarkupAbstractTemplate().getJdbc(sqls[0]);
					if(type.contains("array")){
						this.value=jt.queryForList(rst.getSqlString(),toClass(),rst.getParamObjects());
					}else{
						this.value=jt.queryForObject(rst.getSqlString(),toClass(),rst.getParamObjects().toArray(new Object[]{}));
					}
					return;
				}
			}
		}
		this.value=convertValue(this.type, value2);
	}
	public Object convertValue(String typev,String value2){
		Object result=null;
		if(typev.equals("date")){
			result=DateTools.parse(value2);
		}else if(typev.equals("int")){
			result=ClassUtil.convertValueToRequiredType(value2,Integer.class);
		}else if(typev.equals("long")){
			result=ClassUtil.convertValueToRequiredType(value2,Long.class);
		}else if(typev.equals("float")){
			result=ClassUtil.convertValueToRequiredType(value2,Float.class);
		}else if(typev.equals("double")){
			result=ClassUtil.convertValueToRequiredType(value2,Double.class);
		}else if(typev.equals("number")){
			result=getNumberValue(value2);
		}else if(typev.equals("array")){
			result=buildStr(value2);
		}else if(typev.equals("array-char")||typev.equals("array_char")){
			if(value2.length()==0){
				return result;
			}
			result=value2.split(split);
		}else if(typev.equals("array-date")||typev.equals("array_date")||typev.equals("array-time")||typev.equals("array_time")){
			if(value2.length()==0){
				return result;
			}
			String vs[]=value2.split(split);
			Object[] objs=new Object[vs.length];
			for(int i=0;i<vs.length;i++){
				Date date=DateTools.parse(vs[i]);
				objs[i]=(typev.equals("array-time")||typev.equals("array_time"))?new Time(date.getTime()):date;
			}
			result=objs;
		}else if(typev.equals("array-number")){
			String vs[]=value2.split(split);
			Object[] objs=new Object[vs.length];
			for(int i=0;i<objs.length;i++){
				String v=vs[i];
				objs[i]=getNumberValue(v);
			}
		}else if(typev.equals("timestamp")||typev.equals("time")){
			result=new Timestamp(DateTools.parse(value2).getTime());
		}else{
			result=value2;
		}
		return result;
	}
	
	public String buildStr(String val){
		StringBuffer sb=new StringBuffer();
		String[] vs=val.split(split);
		for(int i=0;i<vs.length;i++){
			sb.append("'"+vs[i]+"'");
			if(i<vs.length-1){
				sb.append(",");
			}
		}
		return sb.toString();
	}

	public String getEncode() {
		return encode;
	}

	public void setEncode(String encode) {
		this.encode = encode;
	}
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	private Object getNumberValue(String value2){
		Object result=value2;
		if(!SmlTools.isEmpty(value2)&&RegexUtils.isNumber(value2)){
			if(value2.contains(".")){
				result=ClassUtil.convertValueToRequiredType(value2,Double.class);
			}else if(value2.length()<8){
				result=ClassUtil.convertValueToRequiredType(value2,Integer.class);
			}else{
				result=ClassUtil.convertValueToRequiredType(value2,Number.class);
			}
		}
		return result;
	}
	public  Class<?> toClass(){
		String type=this.type.replace("array-","");
		if(type.equals("date")){
			return Date.class;
		}else if(type.equals("number")){
			return Number.class;
		}else if(type.equals("int")){
			return Integer.class;
		}else if(type.equals("long")){
			return Long.class;
		}else if(type.equals("double")){
			return Double.class;
		}
		return String.class;
	}
	public void setDoPreParams(Map<String, String> doPreParams) {
		this.doPreParams = doPreParams;
	}
	
	public static void main(String[] args) {
		System.out.println(Arrays.asList("aa:aaa1".split(":")));
	}
}

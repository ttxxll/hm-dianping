package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;
    private String code;

    public static Result ok(){
        return new Result(true, null, null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, null, data, null, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total, null);
    }
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null, null);
    }
    public static Result fail(String code, String errorMsg){
        return new Result(false, errorMsg, null, null, code);
    }
}

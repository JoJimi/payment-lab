package org.example.cs_study.common.exception.catalog;

import org.example.cs_study.common.exception.BusinessException;
import org.example.cs_study.common.exception.ErrorCode;

public class ProductNotFoundException extends BusinessException {

    public ProductNotFoundException(Long productId) {
        super(ErrorCode.PRODUCT_NOT_FOUND, "존재하지 않는 상품입니다: productId=" + productId);
    }
}

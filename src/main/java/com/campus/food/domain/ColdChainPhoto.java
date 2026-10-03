package com.campus.food.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 冷链超标验收抽检照片（到货测温/车厢/产品外观等取证照片） */
@Entity
@Table(name = "cold_chain_photos")
@Getter
@Setter
@NoArgsConstructor
public class ColdChainPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "cold_chain_case_id")
    private ColdChainCase coldChainCase;

    /** 照片访问地址或对象存储 key */
    @Column(nullable = false, length = 512)
    private String photoUrl;

    /** 照片说明，如 车厢温度计/产品批次标签/酸奶外观 */
    @Column(length = 256)
    private String caption;

    @Column(nullable = false)
    private java.time.LocalDateTime uploadedAt = java.time.LocalDateTime.now();
}

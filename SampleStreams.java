import javax.transaction.Transactional;
package pc.dao;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.hibernate.query.NativeQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import pc.dto.ServicePointDTO;
import pc.dto.GoodsPriceDTO;
import pc.entity.*;
import pc.service.ServicePointsInitializeService;

import javax.persistence.criteria.*;
import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Comparator.nullsFirst;

@Repository
public class ServicePointDAO {


    @Transactional
    public synchronized void servicePointsInitialize(int clientId, int userId) {

        Session session = sessionFactory.getCurrentSession();
        CriteriaBuilder builder = session.getCriteriaBuilder();


        CriteriaQuery<ServicePointValidated> query = builder.createQuery(ServicePointValidated.class);
        Root<ServicePointValidated> root = query.from(ServicePointValidated.class);

        query.select(root);

        ArrayList<Predicate> predicatesList = new ArrayList<>();
        predicatesList.add(builder.isNotNull(root.get(ServicePointValidated_.COORDINATE_N)));
        predicatesList.add(builder.notEqual(root.get(ServicePointValidated_.COORDINATE_N), 0));
        predicatesList.add(builder.isNotNull(root.get(ServicePointValidated_.COORDINATE_E)));
        predicatesList.add(builder.notEqual(root.get(ServicePointValidated_.COORDINATE_E), 0));

        predicatesList.add(builder.equal(root.get(ServicePointValidated_.CLIENTID), clientId));
        predicatesList.add(builder.equal(root.get(ServicePointValidated_.USERID), userId));
        query.where(predicatesList.toArray(new Predicate[0]));

        Stream<ServicePointValidated> queryResult = session.createQuery(query).stream();

        Set<ServicePointDTO> servicePoints = new HashSet<>();

        if (queryResult != null) queryResult.forEach(s -> {
            List<GoodsPriceDTO> goodsPrices = new ArrayList<>();

            Comparator<GoodsPrice> comparator = Comparator.comparing(GoodsPrice::getPriceDate, nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(GoodsPrice::getId);

            Map<String, Optional<GoodsPrice>> sortedGoodsPrices = s.getGoodsPrices().stream()
                    .collect(Collectors.groupingBy(GoodsPrice::getName, Collectors.reducing(BinaryOperator.maxBy(comparator))));

            sortedGoodsPrices.values().forEach(g -> g.ifPresent(goodsPrice -> goodsPrices.add(new GoodsPriceDTO(goodsPrice))));

            servicePoints.add(new ServicePointDTO(
                    s.getId(),
                    s.getNetworkid(),
                    s.getNetworkname(),
                    s.getCoordinateN(),
                    s.getCoordinateE(),
                    s.getName(),
                    s.getAddress(),
                    s.getRegionid(),
                    s.getState(),
                    s.getCurrency(),
                    goodsPrices,
                    s.getGlobalownerid()
            ));
        });
        putServicePoints(clientId, userId, servicePoints);
    }

    @Transactional
    public Set<ServicePointDTO> checkContractsRestrictions(Set<ServicePointDTO> servicePoints, Integer contract) {
        Session session = sessionFactory.getCurrentSession();
        NativeQuery<Object[]> contractrestrictions;
        servicePoints.forEach(s -> s.setAllowed(false));
        contractrestrictions = session.createSQLQuery(String.format(
                "SELECT servicetableid, id, sign\n" +
                        "FROM opcl.contractrestrictions\n" +
                        "WHERE contractid = %d AND servicetableid in (5, 4, 3, 2)", contract));

        Map<Integer, List<Integer>> allowedEntities = contractrestrictions.stream()
                .filter(s -> ((BigDecimal) s[2]).intValue() == 1)
                .collect(Collectors.groupingBy(s -> ((BigDecimal) s[0]).intValue()))
                .entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, s ->
                                s.getValue().stream().map(l -> ((BigDecimal) l[1]).intValue()).collect(Collectors.toList())));

        Map<Integer, List<Integer>> disAllowedEntities = contractrestrictions.stream()
                .filter(s -> ((BigDecimal) s[2]).intValue() == 0)
                .collect(Collectors.groupingBy(s -> ((BigDecimal) s[0]).intValue()))
                .entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey, s ->
                                s.getValue().stream().map(l -> ((BigDecimal) l[1]).intValue()).collect(Collectors.toList())));

        for (int i = 5; i >= 2; i--) {
            int level = i;
            if (allowedEntities.containsKey(level) && !disAllowedEntities.containsKey(level))
                servicePoints.forEach(s -> {
                    if (allowedEntities.get(level).contains(s.getProperty(level)))
                        s.setAllowed(true);
                });
            else if (!allowedEntities.containsKey(level) && disAllowedEntities.containsKey(level))
                servicePoints.forEach(s -> {
                    if (level == 5) s.setAllowed(true);
                    if (disAllowedEntities.get(level).contains(s.getProperty(level)))
                        s.setAllowed(false);
                });
            else if (allowedEntities.containsKey(level) && disAllowedEntities.containsKey(level))
                servicePoints.forEach(s -> {
                    if (level == 5) s.setAllowed(true);
                    if (allowedEntities.get(level).contains(s.getProperty(level)))
                        s.setAllowed(true);
                    if (disAllowedEntities.get(level).contains(s.getProperty(level)))
                        s.setAllowed(false);
                });
            else if (level == 5) servicePoints.forEach(s -> s.setAllowed(true));
        }

        return servicePoints.stream().filter(ServicePointDTO::isAllowed).collect(Collectors.toSet());
    }
}